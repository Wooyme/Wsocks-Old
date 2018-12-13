package me.wooy.proxy.client

import io.vertx.core.AbstractVerticle
import io.vertx.core.MultiMap
import io.vertx.core.buffer.Buffer
import io.vertx.core.datagram.DatagramPacket
import io.vertx.core.datagram.DatagramSocket
import io.vertx.core.http.HttpClient
import io.vertx.core.http.WebSocket
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.core.net.NetSocket
import io.vertx.core.net.SocketAddress
import me.wooy.proxy.data.*
import java.lang.IllegalStateException
import java.net.Inet4Address
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class ClientSocks5:AbstractVerticle() {
  private val logger = LoggerFactory.getLogger(ClientSocks5::class.java)
  private lateinit var udpServer:DatagramSocket
  private lateinit var httpClient: HttpClient
  private lateinit var ws:WebSocket
  private val connectMap = ConcurrentHashMap<String,NetSocket>()
  private val senderMap = ConcurrentHashMap<String,SocketAddress>()
  private val address = Inet4Address.getByName("127.0.0.1").address
  private lateinit var remoteIp: String
  private var remotePort: Int = 0
  private var localPort:Int = 0
  private lateinit var user: String
  private lateinit var pwd: String
  override fun start() {
    super.start()
    httpClient = vertx.createHttpClient()
    if(config().getBoolean("ui")){
      vertx.eventBus().consumer<JsonObject>("local-init") {
        remoteIp = it.body().getString("remote.ip")
        remotePort = it.body().getInteger("remote.port")
        localPort = it.body().getInteger("local.port")
        user = it.body().getString("user")
        pwd = it.body().getString("pass")
        initWebSocket(remoteIp,remotePort,user,pwd)
        initSocksServer(localPort)
        initUdpServer()
      }
      vertx.eventBus().consumer<JsonObject>("remote-modify") {
        remoteIp = it.body().getString("remote.ip")
        remotePort = it.body().getInteger("remote.port")
        user = it.body().getString("user")
        pwd = it.body().getString("pass")
        initWebSocket(remoteIp,remotePort,user,pwd)
      }
      vertx.eventBus().consumer<String>("remote-re-connect"){
        initWebSocket(remoteIp,remotePort,user,pwd)
      }
    }else{
      val remoteIp = config().getString("remote.ip")
      val remotePort = config().getInteger("remote.port")
      val localPort = config().getInteger("local.port")
      val user = config().getString("user")
      val pwd = config().getString("pass")
      initWebSocket(remoteIp,remotePort,user,pwd)
      initSocksServer(localPort)
      initUdpServer()
    }
  }

  private fun initWebSocket(remoteIp:String,remotePort:Int,user:String,pass:String){
    if(this::ws.isInitialized){
      try { ws.close() }catch (e:IllegalStateException){ /*异常属于正常情况，不用处理*/ }
    }else{
      //只在第一次初始化的设置
      vertx.setPeriodic(5000) {
        if(this::ws.isInitialized)
          ws.writePing(Buffer.buffer())
      }
    }
    httpClient.websocket(remotePort
      ,remoteIp
      ,"/proxy"
      , MultiMap.caseInsensitiveMultiMap()
      .add("user",user).add("pass",pass)){ webSocket ->
      webSocket.writePing(Buffer.buffer())
      webSocket.binaryMessageHandler {buffer->
        if (buffer.length() < 4) {
          return@binaryMessageHandler
        }
        when (buffer.getIntLE(0)) {
          Flag.CONNECT_SUCCESS.ordinal -> wsConnectedHandler(ConnectSuccess(buffer).uuid)
          Flag.EXCEPTION.ordinal -> wsExceptionHandler(Exception(buffer))
          Flag.RAW.ordinal -> wsReceivedRawHandler(RawData(buffer))
          Flag.UDP.ordinal -> wsReceivedUDPHandler(RawUDPData(buffer))
          else -> logger.warn(buffer.getIntLE(0))
        }
      }.exceptionHandler {t->
        logger.warn(t)
        ws.close()
        initWebSocket(remoteIp, remotePort, user, pass)
      }
      this.ws = webSocket
      logger.info("Connected to remote server")
      vertx.eventBus().publish("status-modify",JsonObject().put("status","$remoteIp:$remotePort"))
    }
  }

  private fun initSocksServer(port: Int){
    vertx.createNetServer().connectHandler { socket ->
      if(!this::ws.isInitialized){
        socket.close()
        return@connectHandler
      }
      val uuid = UUID.randomUUID().toString()
      socket.handler {
        bufferHandler(uuid,socket,it)
      }.closeHandler {
        connectMap.remove(uuid)
      }
    }.listen(port){
      logger.info("Listen at $port")
    }
  }

  private fun initUdpServer(){
    udpServer = vertx.createDatagramSocket().handler {
      if(!this::ws.isInitialized)
        return@handler
      udpPacketHandler(it)
    }.listen(29799,"127.0.0.1"){
      logger.info("UDP Server listen at 29799")
    }
  }

  private fun bufferHandler(uuid: String,netSocket: NetSocket,buffer: Buffer){
    val version = buffer.getByte(0)
    if(version !=0x05.toByte()){
      netSocket.close()
    }
    when{
      buffer.getByte(1).toInt()+2==buffer.length()->{
        handshakeHandler(netSocket)
      }
      else-> requestHandler(uuid,netSocket,buffer)
    }
  }

  private fun handshakeHandler(netSocket: NetSocket){
    netSocket.write(Buffer.buffer().appendByte(0x05.toByte()).appendByte(0x00.toByte()))
  }

  private fun requestHandler(uuid: String,netSocket: NetSocket,buffer: Buffer){
    /*
    * |VER|CMD|RSV|ATYP|DST.ADDR|DST.PORT|
    * -----------------------------------------
    * | 1 | 1 |0x0| 1  |Variable|   2    |
    * -----------------------------------------
    * */
    val cmd = buffer.getByte(1)
    val addressType = buffer.getByte(3)
    val (host,port) = when(addressType){
      0x01.toByte()->{
        val host = Inet4Address.getByAddress(buffer.getBytes(5,9)).toString()
        val port = buffer.getShort(9).toInt()
        host to port
      }
      0x03.toByte()->{
        val hostLen = buffer.getByte(4).toInt()
        val host = buffer.getString(5,5+hostLen)
        val port = buffer.getShort(5+hostLen).toInt()
        host to port
      }
      else->{
        netSocket.write(Buffer.buffer()
          .appendByte(0x05.toByte())
          .appendByte(0x08.toByte()))
        return
      }
    }
    when(cmd){
      0x01.toByte()->{
        tryConnect(uuid,netSocket,host, port)
      }
      0x03.toByte()->{
        netSocket.write(Buffer.buffer()
          .appendByte(0x05.toByte())
          .appendByte(0x00.toByte())
          .appendByte(0x00.toByte())
          .appendByte(0x01.toByte())
          .appendBytes(address).appendUnsignedShortLE(29799))
      }
      else->{
        netSocket.write(Buffer.buffer()
          .appendByte(0x05.toByte())
          .appendByte(0x07.toByte()))
        return
      }
    }
  }

  private fun udpPacketHandler(packet:DatagramPacket){
    val buffer = packet.data()
    if(buffer.getByte(0)!=0x0.toByte() || buffer.getByte(1)!=0x0.toByte()){
      return
    }
    if(buffer.getByte(2)!=0x0.toByte()){
      return
    }
    val addressType = buffer.getByte(3)
    val (host,port,data ) = when(addressType){
      0x01.toByte()->{
        val host = Inet4Address.getByAddress(buffer.getBytes(5,9)).toString()
        val port = buffer.getShort(9).toInt()
        val data = buffer.getBuffer(11,buffer.length())
        Triple(host,port,data)
      }
      0x03.toByte()->{
        val hostLen = buffer.getByte(4).toInt()
        val host = buffer.getString(5,5+hostLen)
        val port = buffer.getShort(5+hostLen).toInt()
        val data = buffer.getBuffer(7+hostLen,buffer.length())
        Triple(host,port,data)
      }
      else-> return
    }
    val uuid = UUID.randomUUID().toString()
    senderMap[uuid] = packet.sender()
    ws.writeBinaryMessage(RawUDPData.create(uuid, host, port, data).toBuffer())
  }

  private fun tryConnect(uuid:String,netSocket: NetSocket,host:String,port:Int){
    connectMap[uuid] = netSocket
    ws.writeBinaryMessage(ClientConnect.create(uuid,host,port).toBuffer())
  }

  private fun wsConnectedHandler(uuid:String){
    val netSocket = connectMap[uuid]?:return
    //建立连接后修改handler
    netSocket.handler {
      ws.writeBinaryMessage(RawData.create(uuid,it).toBuffer())
    }
    val buffer = Buffer.buffer()
      .appendByte(0x05.toByte())
      .appendByte(0x00.toByte())
      .appendByte(0x00.toByte())
      .appendByte(0x01.toByte())
      .appendBytes(ByteArray(6){0x0})
    netSocket.write(buffer)
  }

  private fun wsReceivedRawHandler(data: RawData){
    val netSocket = connectMap[data.uuid]?:return
    netSocket.write(data.data)
  }

  private fun wsReceivedUDPHandler(data:RawUDPData){
    val sender = senderMap[data.uuid]?:return
    udpServer.send(data.data,sender.port(),sender.host()){}
  }

  private fun wsExceptionHandler(e:Exception){
    connectMap.remove(e.uuid)?.close()
    logger.warn(e.message)
  }
}

