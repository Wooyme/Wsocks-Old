package me.wooy.proxy.server

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.buffer.Buffer
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
import org.apache.commons.codec.digest.DigestUtils
import java.io.File
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.BadPaddingException

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
  private fun ServerWebSocket.writeBinaryMessageWithOffset(data: Buffer) {
    if (offset == 0) {
      this.writeBinaryMessage(data)
    } else {
      val bytes = ByteArray(offset)
      Random().nextBytes(bytes)
      this.writeBinaryMessage(Buffer.buffer(bytes).appendBuffer(data))
    }
  }
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
        Aes.raw = array+ByteArray(16 - array.size){ 0x06 }
      else
        Aes.raw = array
      println(Aes.raw.contentToString())
      println(DigestUtils.md5Hex(Aes.raw))
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
    var md5 = sock.headers().get("md5")
    var user = sock.headers().get("user")
    var pass = sock.headers().get("pass")
    if(user==null || pass==null || md5 ==null){
      if(sock.path().split("/").size>=4){
        user = sock.path().split("/")[1]
        pass = sock.path().split("/")[2]
        md5 = sock.path().split("/")[3]
      }else{
        sock.reject()
        return
      }
    }
    if(userList[user]!=pass){
      sock.reject()
      return
    }
    if(DigestUtils.md5Hex(Aes.raw)!=md5){
      sock.reject()
      return
    }
    sock.binaryMessageHandler { _buffer ->
      GlobalScope.launch(vertx.dispatcher()) {
        val buffer = if(offset!=0) _buffer.getBuffer(offset,_buffer.length()) else _buffer
        try {
          when (buffer.getIntLE(0)) {
            Flag.CONNECT.ordinal -> clientConnectHandler(sock, ClientConnect(buffer))
            Flag.RAW.ordinal -> clientRawHandler(sock, RawData(buffer))
            Flag.HTTP.ordinal -> clientRequestHandler(sock, HttpData(buffer))
            Flag.UDP.ordinal -> clientUDPHandler(sock, RawUDPData(buffer))
            else -> logger.error(buffer.getIntLE(0))
          }
        }catch (e:BadPaddingException){
          sock.writeBinaryMessageWithOffset(Exception.create("","Invalid key").toBuffer())
          sock.reject()
        }
      }
    }
    sock.accept()
  }

  private suspend fun clientConnectHandler(sock: ServerWebSocket, data: ClientConnect){
    try {
      val net = netClient.connectAwait(data.port, data.host)
      net.handler { buffer->
        sock.writeBinaryMessageWithOffset(RawData.create(data.uuid,buffer).toBuffer())
      }.closeHandler {
        localMap.remove(data.uuid)
      }
      localMap[data.uuid] = net
    }catch (e:Throwable){
      logger.warn(e.message)
      sock.writeBinaryMessageWithOffset(Exception.create(data.uuid,e.localizedMessage).toBuffer())
      return
    }
    sock.writeBinaryMessageWithOffset(ConnectSuccess.create(data.uuid).toBuffer())
  }

  private fun clientRawHandler(sock: ServerWebSocket, data: RawData){
    val net = localMap[data.uuid]
    net?.write(data.data)?:let{
      sock.writeBinaryMessageWithOffset(Exception.create(data.uuid,"Remote socket has closed").toBuffer())
    }
  }

  private suspend fun clientRequestHandler(sock: ServerWebSocket, data:HttpData){
    try{
      val net = netClient.connectAwait(data.port,data.host)
      net.handler {buffer->
        sock.writeBinaryMessageWithOffset(RawData.create(data.uuid,buffer).toBuffer())
      }
      net.write(data.data)
    }catch (e:Throwable){
      logger.warn(e)
      sock.writeBinaryMessageWithOffset(Exception.create(data.uuid,e.localizedMessage).toBuffer())
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
      ws.writeBinaryMessageWithOffset(RawUDPData.create(uuid,"",0,it.data()).toBuffer())
    }
  }
}
