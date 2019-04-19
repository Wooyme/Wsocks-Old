package me.wooy.proxy.client

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.MultiMap
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.WebSocket
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.Logger
import io.vertx.core.logging.LoggerFactory
import io.vertx.core.net.NetServer
import io.vertx.core.net.NetSocket
import me.wooy.proxy.common.UserInfo
import me.wooy.proxy.data.*
import me.wooy.proxy.jni.KCP
import org.apache.commons.lang3.RandomStringUtils
import java.net.Inet4Address
import java.net.InetAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class ClientWebSocket : AbstractVerticle() {
  private val logger: Logger = LoggerFactory.getLogger(ClientWebSocket::class.java)
  private lateinit var netServer: NetServer
  private val connectMap = ConcurrentHashMap<String, NetSocket>()
  private val address = Inet4Address.getByName("127.0.0.1").address
  private var bufferSizeHistory: Long = 0L
  private var port = 1080
  private var remotePort = 1888
  private lateinit var remoteIp: String
  private lateinit var userInfo: UserInfo
  private val httpClient: HttpClient by lazy { vertx.createHttpClient() }
  private val ds by lazy { vertx.createDatagramSocket() }
  private lateinit var ws: WebSocket

  override fun start(future: Future<Void>) {
    vertx.eventBus().consumer<JsonObject>("config-modify") {
      if(it.body().size()!=0){
        port = it.body().getInteger("local.port") ?: 1080
        remotePort = it.body().getInteger("remote.port") ?: 1888
        remoteIp = it.body().getString("remote.ip")
        userInfo = UserInfo.fromJson(it.body())
      }
      login()
      initSocksServer(port)
    }

    vertx.setPeriodic(5000) {
      if (this::ws.isInitialized)
        ws.writePing(Buffer.buffer())
    }
    vertx.setPeriodic(2 * 1000) {
      vertx.eventBus().publish("net-status-update", "${bufferSizeHistory shr 11}kb/s")
      bufferSizeHistory = 0
    }

    future.complete()
  }

  override fun stop() {
    netServer.close()
    super.stop()
  }

  private fun login() {
    if (!this::remoteIp.isInitialized)
      return
    val randomKey = "/"+RandomStringUtils.randomAlphanumeric(5)
    httpClient.websocket(remotePort
        , InetAddress.getByName(remoteIp).hostAddress
        , randomKey
        , MultiMap.caseInsensitiveMultiMap()
        .add(RandomStringUtils.randomAlphanumeric(Random().nextInt(10)+1)
            ,userInfo.secret())) { webSocket ->
      webSocket.writePing(Buffer.buffer())
      webSocket.binaryMessageHandler { _buffer ->
        if (_buffer.length() < 4) return@binaryMessageHandler

        val buffer = if (userInfo.offset != 0) _buffer.getBuffer(userInfo.offset, _buffer.length()) else _buffer
        when (buffer.getIntLE(0)) {
          Flag.CONNECT_SUCCESS.ordinal -> wsConnectedHandler(ConnectSuccess(userInfo,buffer).uuid)
          Flag.EXCEPTION.ordinal -> wsExceptionHandler(Exception(userInfo,buffer))
          Flag.RAW.ordinal -> {
            bufferSizeHistory += buffer.length()
            wsReceivedRawHandler(RawData(userInfo,buffer))
          }
          else -> logger.warn(buffer.getIntLE(0))
        }
      }.exceptionHandler { t ->
        logger.warn(t)
        ws.close()
        login()
      }
      this.ws = webSocket
      logger.info("Connected to remote server")
      vertx.eventBus().publish("status-modify", JsonObject().put("status", "$remoteIp:$remotePort"))
    }
    initKcp(randomKey)
  }

  private fun initSocksServer(port: Int) {
    if (this::netServer.isInitialized) {
      this.netServer.close()
    }
    this.netServer = vertx.createNetServer().connectHandler { socket ->
      if (!this::ws.isInitialized) {
        socket.close()
        return@connectHandler
      }
      val uuid = UUID.randomUUID().toString()
      socket.handler {
        bufferHandler(uuid, socket, it)
      }.closeHandler {
        connectMap.remove(uuid)
      }.exceptionHandler {
        socket.close()
      }
    }.listen(port) {
      logger.info("Listen at $port")
    }
  }


  private fun bufferHandler(uuid: String, netSocket: NetSocket, buffer: Buffer) {
    val version = buffer.getByte(0)
    if (version != 0x05.toByte()) {
      netSocket.close()
    }
    when {
      buffer.getByte(1).toInt() + 2 == buffer.length() -> {
        handshakeHandler(netSocket)
      }
      else -> requestHandler(uuid, netSocket, buffer)
    }
  }

  private fun handshakeHandler(netSocket: NetSocket) {
    netSocket.write(Buffer.buffer().appendByte(0x05.toByte()).appendByte(0x00.toByte()))
  }

  private fun requestHandler(uuid: String, netSocket: NetSocket, buffer: Buffer) {
    /*
    * |VER|CMD|RSV|ATYP|DST.ADDR|DST.PORT|
    * -----------------------------------------
    * | 1 | 1 |0x0| 1  |Variable|   2    |
    * -----------------------------------------
    * */
    val cmd = buffer.getByte(1)
    val addressType = buffer.getByte(3)
    logger.info("UUID:$uuid,$cmd,$addressType")
    val (host, port) = when (addressType) {
      0x01.toByte() -> {
        val host = Inet4Address.getByAddress(buffer.getBytes(4, 8)).toString().removePrefix("/")
        val port = buffer.getShort(8).toInt()
        logger.info("UUID:$uuid,Connect to $host:$port")
        host to port
      }
      0x03.toByte() -> {
        val hostLen = buffer.getByte(4).toInt()
        val host = buffer.getString(5, 5 + hostLen)
        val port = buffer.getShort(5 + hostLen).toInt()
        host to port
      }
      else -> {
        netSocket.write(Buffer.buffer()
            .appendByte(0x05.toByte())
            .appendByte(0x08.toByte()))
        return
      }
    }
    when (cmd) {
      0x01.toByte() -> {
        tryConnect(uuid, netSocket, host, port)
      }
      0x03.toByte() -> {
        netSocket.write(Buffer.buffer()
            .appendByte(0x05.toByte())
            .appendByte(0x00.toByte())
            .appendByte(0x00.toByte())
            .appendByte(0x01.toByte())
            .appendBytes(address).appendUnsignedShortLE(29799))
      }
      else -> {
        netSocket.write(Buffer.buffer()
            .appendByte(0x05.toByte())
            .appendByte(0x07.toByte()))
        return
      }
    }
  }

  private fun WebSocket.writeBinaryMessageWithOffset(data: Buffer) {
    if (userInfo.offset == 0) {
      this.writeBinaryMessage(data)
    } else {
      val bytes = ByteArray(userInfo.offset)
      Random().nextBytes(bytes)
      this.writeBinaryMessage(Buffer.buffer(bytes).appendBuffer(data))
    }
  }


  private fun tryConnect(uuid: String, netSocket: NetSocket, host: String, port: Int) {
    connectMap[uuid] = netSocket
    ws.writeBinaryMessageWithOffset(ClientConnect.create(userInfo,uuid, host, port))
  }

  private fun wsConnectedHandler(uuid: String) {
    val netSocket = connectMap[uuid] ?: return
    //建立连接后修改handler
    netSocket.handler {
      ws.writeBinaryMessageWithOffset(RawData.create(userInfo,uuid, it))
    }
    val buffer = Buffer.buffer()
        .appendByte(0x05.toByte())
        .appendByte(0x00.toByte())
        .appendByte(0x00.toByte())
        .appendByte(0x01.toByte())
        .appendBytes(ByteArray(6) { 0x0 })
    netSocket.write(buffer)
  }

  private fun wsReceivedRawHandler(data: RawData) {
    val netSocket = connectMap[data.uuid] ?: return
    netSocket.write(data.data)
  }

  private fun wsExceptionHandler(e: Exception) {
    connectMap.remove(e.uuid)?.close()
    if(e.message.isNotEmpty())
      logger.warn(e.message)
  }

  private fun initKcp(randomKey:String){
    val kcpTest = object : KCP(0x11223344) {
      override fun output(buffer: ByteArray, size: Int) {
        ds.send(Buffer.buffer().appendBytes(buffer.sliceArray(0..size)),3333,remoteIp){}
      }
    }
    kcpTest.NoDelay(1, 10, 2, 1)
    kcpTest.WndSize(256,256)

    vertx.setPeriodic(10){
      kcpTest.Update(Date().time)
    }
    val data = ByteArray(1024*1024)
    vertx.setPeriodic(10){
      var len = kcpTest.Recv(data)
      while(len>0){
        val buffer = Buffer.buffer(data.sliceArray(0 until len))
        when (buffer.getIntLE(0)) {
          Flag.CONNECT_SUCCESS.ordinal -> wsConnectedHandler(ConnectSuccess(userInfo,buffer).uuid)
          Flag.EXCEPTION.ordinal -> wsExceptionHandler(Exception(userInfo,buffer))
          Flag.RAW.ordinal -> {
            bufferSizeHistory += buffer.length()
            wsReceivedRawHandler(RawData(userInfo,buffer))
          }
          else -> logger.warn(buffer.getIntLE(0))
        }
        len = kcpTest.Recv(data)
      }
    }
    ds.handler {
      kcpTest.Input(it.data().bytes)
    }.listen(9999,"0.0.0.0"){
      println("DS Listen at 9999")
    }
    ds.send(Buffer.buffer().appendString("login").appendString(randomKey),3333,remoteIp){}
  }
}

