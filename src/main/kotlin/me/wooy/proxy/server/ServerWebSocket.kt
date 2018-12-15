package me.wooy.proxy.server

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.datagram.DatagramSocket
import io.vertx.core.http.HttpServer
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.core.net.NetClient
import io.vertx.core.net.NetSocket
import io.vertx.core.net.SocketAddress
import io.vertx.kotlin.core.net.connectAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import me.wooy.proxy.data.*
import me.wooy.proxy.encryption.Aes
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log2

class ServerWebSocket:AbstractVerticle() {
  private val logger = LoggerFactory.getLogger(ServerWebSocket::class.java)
  private lateinit var udpClient:DatagramSocket
  private lateinit var netClient: NetClient
  private lateinit var httpServer:HttpServer
  private lateinit var userList:Map<String,String>
  private var port:Int = 1888
  private var offset:Int = 0

  private val localMap: ConcurrentHashMap<String,NetSocket> = ConcurrentHashMap()
  private val senderMap = ConcurrentHashMap<SocketAddress,Pair<ServerWebSocket,String>>()
  override fun start(startFuture: Future<Void>) {
    udpClient = vertx.createDatagramSocket()
    initUdpClient()
    netClient = vertx.createNetClient()
    httpServer = vertx.createHttpServer()
    httpServer.websocketHandler(this::socketHandler)
    configFromFile(config().getString("config.path"))
    vertx.executeBlocking<HttpServer>({
      httpServer.listen(port,it.completer())
    }){
      logger.info("Proxy server listen at $port")
      startFuture.complete()
    }
  }

  private fun configFromFile(filename:String){
    val config = JsonObject(File(filename).readText())
    if(config.containsKey("port")) {
      port = config.getInteger("port")
    }
    if(config.containsKey("key")){
      val array = config.getString("key").toByteArray()
      if(16!=array.size)
        Aes.raw = array+ByteArray(16 - array.size){ 0x00 }
      else
        Aes.raw = array
    }else{
      logger.info("配置文件未设置秘钥，默认使用${Aes.raw.joinToString("") { it.toString() }}")
    }
    if(config.containsKey("offset")){
      offset = config.getInteger("offset")
    }else{
      logger.info("配置文件未设置数据偏移，默认为0")
    }
    if(!config.containsKey("users")){
      logger.error("配置文件未设置用户，程序退出")
      System.exit(-1)
    }
    userList = config.getJsonObject("users").map {
      it.key to it.value.toString()
    }.toMap()
  }

  private fun socketHandler(sock: ServerWebSocket){
    val user = sock.headers().get("user")
    val pass = sock.headers().get("pass")
    if(user==null || pass==null){
      sock.reject()
      return
    }

    if(userList[user]!=pass){
      sock.reject()
      return
    }
    sock.binaryMessageHandler { _buffer ->
      GlobalScope.launch(vertx.dispatcher()) {
        val buffer = if(offset!=0) _buffer.getBuffer(offset,_buffer.length()) else _buffer
        when (buffer.getIntLE(0)) {
          Flag.CONNECT.ordinal -> clientConnectHandler(sock, ClientConnect(buffer))
          Flag.RAW.ordinal -> clientRawHandler(sock, RawData(buffer))
          Flag.HTTP.ordinal -> clientRequestHandler(sock, HttpData(buffer))
          Flag.UDP.ordinal -> clientUDPHandler(sock, RawUDPData(buffer))
          else->logger.error(buffer.getIntLE(0))
        }
      }
    }
    sock.accept()
  }

  private suspend fun clientConnectHandler(sock: ServerWebSocket, data:ClientConnect){
    try {
      val net = netClient.connectAwait(data.port, data.host)
      net.handler { buffer->
        sock.writeBinaryMessage(RawData.create(data.uuid,buffer).toBuffer())
      }.closeHandler {
        localMap.remove(data.uuid)
      }
      localMap[data.uuid] = net
    }catch (e:Throwable){
      logger.warn(e.message)
      sock.writeBinaryMessage(Exception.create(data.uuid,e.localizedMessage).toBuffer())
      return
    }
    sock.writeBinaryMessage(ConnectSuccess.create(data.uuid).toBuffer())
  }

  private fun clientRawHandler(sock: ServerWebSocket, data: RawData){
    val net = localMap[data.uuid]
    net?.write(data.data)?:let{
      sock.writeBinaryMessage(Exception.create(data.uuid,"Remote socket has closed").toBuffer())
    }
  }

  private suspend fun clientRequestHandler(sock: ServerWebSocket, data:HttpData){
    try{
      val net = netClient.connectAwait(data.port,data.host)
      net.handler {buffer->
        sock.writeBinaryMessage(RawData.create(data.uuid,buffer).toBuffer())
      }
      net.write(data.data)
    }catch (e:Throwable){
      logger.warn(e)
      sock.writeBinaryMessage(Exception.create(data.uuid,e.localizedMessage).toBuffer())
      return
    }
  }

  private fun clientUDPHandler(sock: ServerWebSocket, data:RawUDPData){
    senderMap[SocketAddress.inetSocketAddress(data.port,data.host)] = sock to data.uuid
    udpClient.send(data.data,data.port,data.host){}
  }

  private fun initUdpClient(){
    udpClient.handler {
      val (ws,uuid) = senderMap.remove(it.sender())?:return@handler
      ws.writeBinaryMessage(RawUDPData.create(uuid,"",0,it.data()).toBuffer())
    }
  }
}
