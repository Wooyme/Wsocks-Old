package me.wooy.proxy.client

import io.vertx.core.AbstractVerticle
import io.vertx.core.DeploymentOptions
import io.vertx.core.Future
import io.vertx.core.MultiMap
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.eventbus.EventBus
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
import me.wooy.proxy.server.ServerWebSocket
import org.apache.commons.lang3.RandomStringUtils
import org.pcap4j.core.BpfProgram
import org.pcap4j.core.PacketListener
import org.pcap4j.core.PcapNetworkInterface
import org.pcap4j.core.Pcaps
import org.pcap4j.packet.TcpPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.IllegalBlockSizeException

class ClientWebSocket : AbstractVerticle() {
  class KcpWorker(private val eventBus: EventBus,private val kcp:KCP,private val address:String):AbstractVerticle(){
    private val data = ByteArray(16*1024*1024)
    override fun start(){
      super.start()
      eventBus.localConsumer<Buffer>("$address-input"){
        kcp.Input(it.body().bytes)
      }
      vertx.setPeriodic(10){
        kcp.Update(Date().time)
        var len = kcp.Recv(data)
        while(len>0){
          eventBus.send("$address-recv",Buffer.buffer(data.sliceArray(0 until len)), DeliveryOptions().setLocalOnly(true))
          len = kcp.Recv(data)
        }
      }
    }
    fun input(buf:Buffer){
      eventBus.send("$address-input",buf,DeliveryOptions().setLocalOnly(true))
    }

    fun recv(callback:(Buffer)->Any){
      eventBus.localConsumer<Buffer>("$address-recv"){
        callback(it.body())
      }
    }
  }
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
    val randomKey = "/"+RandomStringUtils.randomAlphanumeric(7)
    httpClient.websocket(8888
        , InetAddress.getByName(remoteIp).hostAddress
        , randomKey
        , MultiMap.caseInsensitiveMultiMap()
        .add(RandomStringUtils.randomAlphanumeric(Random().nextInt(10)+1)
            ,userInfo.secret())) { webSocket ->
      webSocket.writePing(Buffer.buffer())
      webSocket.exceptionHandler { t ->
        logger.warn(t)
        ws.close()
        login()
      }
      this.ws = webSocket
      initKcp(randomKey)
      logger.info("Connected to remote server")
      vertx.eventBus().publish("status-modify", JsonObject().put("status", "$remoteIp:$remotePort"))
    }

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
      val uuid = RandomStringUtils.randomAlphanumeric(8)
      socket.handler {
        bufferHandler(uuid, socket, it)
      }.closeHandler {
        ws.writeBinaryMessageWithOffset(Exception.create(userInfo,uuid,""))
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
    val (host, port) = when (addressType) {
      0x01.toByte() -> {
        val host = Inet4Address.getByAddress(buffer.getBytes(4, 8)).toString().removePrefix("/")
        val port = buffer.getShort(8).toInt()
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
    val conv = ByteBuffer.wrap(randomKey.toByteArray()).getInt(0).toLong()
    val kcpTest = object : KCP(conv) {
      override fun output(buffer: ByteArray, size: Int) {
        ws.writeBinaryMessageWithOffset(Buffer.buffer()
            .appendIntLE(Flag.KCP.ordinal)
            .appendBytes(buffer.sliceArray(0 until size)))
      }
    }
    kcpTest.SetMtu(1000)
    kcpTest.NoDelay(1, 10, 2, 1)
    kcpTest.WndSize(128,128)
    val kcpWorker = KcpWorker(vertx.eventBus(),kcpTest,randomKey)
    kcpWorker.recv {buffer->
      when (buffer.getIntLE(0)) {
        Flag.CONNECT_SUCCESS.ordinal -> wsConnectedHandler(ConnectSuccess(userInfo,buffer).uuid)
        Flag.EXCEPTION.ordinal -> wsExceptionHandler(Exception(userInfo,buffer))
        Flag.RAW.ordinal -> {
          bufferSizeHistory += buffer.length()
          try {
            wsReceivedRawHandler(RawData(userInfo, buffer))
          }catch (e:IllegalBlockSizeException){
            logger.info("Error:"+buffer.length()+" Want:${buffer.getIntLE(Int.SIZE_BYTES)}")
          }
        }
        else -> {
          logger.warn(buffer.getIntLE(0))
        }
      }
    }
    vertx.deployVerticle(kcpWorker, DeploymentOptions().setWorker(true))
    vertx.createNetClient().connect(remotePort,remoteIp){
      if(it.failed()) it.cause().printStackTrace()
      else {
        it.result().write(Buffer.buffer("login").appendString(randomKey))
        val device = Pcaps.findAllDevs()[0]
        Thread{
          pcapLoop(device,kcpWorker)
        }.start()
        it.result().pause()
      }
    }
  }

  private fun pcapLoop(device: PcapNetworkInterface,kcpWorker: KcpWorker){
    val snapshotLength = 65536
    val readTimeout = 50
    val handle = device.openLive(snapshotLength, PcapNetworkInterface.PromiscuousMode.PROMISCUOUS,readTimeout)
    handle.setFilter("tcp src port $remotePort and src host $remoteIp", BpfProgram.BpfCompileMode.OPTIMIZE)
    handle.loop(-1, PacketListener {
      val tcp = it.get(TcpPacket::class.java)
      if(tcp.payload.length()>Int.SIZE_BYTES){
        kcpWorker.input(Buffer.buffer(tcp.payload.rawData))
      }
    })
  }
}



