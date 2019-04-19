package me.wooy.proxy.server

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServer
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.DecodeException
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.core.net.NetClient
import io.vertx.core.net.NetClientOptions
import io.vertx.core.net.NetSocket
import io.vertx.core.net.SocketAddress
import io.vertx.kotlin.core.net.connectAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import me.wooy.proxy.common.UserInfo
import me.wooy.proxy.data.*
import me.wooy.proxy.jni.KCP
import me.wooy.proxy.jni.Kcp
import java.net.InetAddress
import java.util.*
import javax.crypto.BadPaddingException
import kotlin.collections.LinkedHashMap

class ServerWebSocket : AbstractVerticle() {
  private val logger = LoggerFactory.getLogger(ServerWebSocket::class.java)
  private lateinit var netClient: NetClient
  private lateinit var httpServer: HttpServer
  private val userMap:MutableMap<String,UserInfo> = LinkedHashMap()
  private val hasLoginMap = HashMap<String,Int>()
  //web 服务器端口
  private val port: Int by lazy { config().getInteger("port")?:1888 }
  private val localMap = LinkedHashMap<String, NetSocket>()
  private val ds by lazy { vertx.createDatagramSocket() }
  private val fakeIp by lazy { config().getString("fake_ip") }
  private val fakePort by lazy { config().getInteger("fake_port") }
  private val udpPort by lazy { config().getInteger("udp_port") }
  private val senderMap:MutableMap<String,KCP> = LinkedHashMap()
  private val kcpMap:MutableMap<SocketAddress,KCP> = LinkedHashMap()

  private fun ServerWebSocket.writeBinaryMessageWithOffset(userInfo: UserInfo,data: Buffer) {
    if (userInfo.offset == 0) {
      senderMap[this.path()]?.Send(data.bytes)
    } else {
      val bytes = ByteArray(userInfo.offset)
      Random().nextBytes(bytes)
      senderMap[this.path()]?.Send(Buffer.buffer(bytes).appendBuffer(data).bytes)
    }
  }

  override fun start(startFuture: Future<Void>) {
    initParams()
    netClient = vertx.createNetClient(NetClientOptions().setReceiveBufferSize(1400))
    httpServer = vertx.createHttpServer()
    httpServer.websocketHandler(this::socketHandler)
    vertx.executeBlocking<HttpServer>({
      httpServer.listen(port, it.completer())
    }) {
      logger.info("Proxy server listen at $port")
      startFuture.complete()
    }
    vertx.eventBus().consumer<JsonObject>("user-modify"){
      val userInfo = UserInfo.fromJson(it.body())
      userMap[userInfo.secret()] = userInfo
    }
    vertx.eventBus().consumer<JsonObject>("user-remove"){
      val username = it.body().getString("user")
      userMap.asIterable().forEach{ v->
        if(v.value.username==username){
          userMap.remove(v.key)
          return@consumer
        }
      }
    }
    initKcp()
  }

  private fun initParams() {
    config().getJsonArray("users").forEach {
      val userInfo = UserInfo.fromJson(it as JsonObject)
      userMap[userInfo.secret()] = userInfo
    }
  }

  private fun socketHandler(sock: ServerWebSocket) {
    val key = sock.headers()
        .firstOrNull { userMap.containsKey(it.value) }?.value ?: return sock.reject()
    val userInfo = userMap[key]?:return sock.reject()
    if(hasLoginMap[key]?.let {
          userInfo.maxLoginDevices in 0..(it - 1)
    } == true){
      return sock.reject()
    }

    hasLoginMap[key] =(hasLoginMap[key]?:0) + 1
    sock.binaryMessageHandler { _buffer ->
      GlobalScope.launch(vertx.dispatcher()) {
        val buffer = if (userInfo.offset != 0) _buffer.getBuffer(userInfo.offset, _buffer.length()) else _buffer
        try {
          when (buffer.getIntLE(0)) {
            Flag.CONNECT.ordinal -> clientConnectHandler(userInfo,sock, ClientConnect(userInfo,buffer))
            Flag.RAW.ordinal -> clientRawHandler(userInfo,sock, RawData(userInfo,buffer))
            Flag.DNS.ordinal -> clientDNSHandler(userInfo,sock, DnsQuery(userInfo,buffer))
            else -> logger.error(buffer.getIntLE(0))
          }
        } catch (e: BadPaddingException) {
          sock.reject()
        } catch(e:DecodeException){

        }
      }
    }.closeHandler {
      hasLoginMap[userInfo.secret()] = hasLoginMap[userInfo.secret()]?.minus(1)?:0

    }
    sock.accept()
  }

  private suspend fun clientConnectHandler(userInfo: UserInfo,sock: ServerWebSocket, data: ClientConnect) {
    val net = try {
      netClient.connectAwait(data.port, InetAddress.getByName(data.host).hostAddress)
    } catch (e: Throwable) {
      logger.warn(e.message)
      sock.writeBinaryMessageWithOffset(userInfo,Exception.create(userInfo,data.uuid, e.localizedMessage))
      return
    }
    net.handler { buffer ->
//      if(sock.writeQueueFull()){
//        net.pause()
//        vertx.setTimer(3000){
//          net.resume()
//        }
//      }
      sock.writeBinaryMessageWithOffset(userInfo,RawData.create(userInfo,data.uuid, buffer))
    }.closeHandler {
      sock.writeBinaryMessageWithOffset(userInfo,Exception.create(userInfo,data.uuid,""))
      localMap.remove(data.uuid)
    }.exceptionHandler {
      localMap.remove(data.uuid)?.close()
    }
    localMap[data.uuid] = net
    sock.writeBinaryMessageWithOffset(userInfo,ConnectSuccess.create(userInfo,data.uuid))
  }

  private fun clientRawHandler(userInfo: UserInfo,sock: ServerWebSocket, data: RawData) {
    val net = localMap[data.uuid]
    net?.write(data.data) ?: let {
      sock.writeBinaryMessageWithOffset(userInfo,Exception.create(userInfo,data.uuid, "Remote socket has closed"))
    }
  }

  private fun clientDNSHandler(userInfo: UserInfo,sock: ServerWebSocket, data: DnsQuery) {
    vertx.executeBlocking<String>({
      val address = try{
        InetAddress.getByName(data.host)?.hostAddress?:"0.0.0.0"
      }catch(e:Throwable){
        "0.0.0.0"
      }
      it.complete(address)
    }){
      sock.writeBinaryMessageWithOffset(userInfo,DnsQuery.create(userInfo,data.uuid, it.result()))
    }
  }

  private fun initKcp(){
    ds.handler {
      if(it.data().getString(0,5)=="login"){
        if(kcpMap.containsKey(it.sender())) return@handler
        val randomKey = it.data().getString(5,it.data().length())
        val raw = Kcp()
        val result = raw.init(fakeIp,fakePort,udpPort,it.sender().host(),it.sender().port())
        val kcp = object : KCP(0x11223344) {
          override fun output(buffer: ByteArray, size: Int) {
            raw.sendBuf(result,buffer.sliceArray(0 until size))
          }
        }
        kcp.WndSize(256,256)
        kcp.NoDelay(1, 10, 2, 1)
        vertx.setPeriodic(10){
          kcp.Update(Date().time)
        }
        kcpMap[it.sender()] = kcp
        senderMap[randomKey] = kcp
      }else {
        kcpMap[it.sender()]?.Input(it.data().bytes)
      }
    }.listen(3333,"0.0.0.0"){
      println("Listen at 3333")
    }
  }
}
