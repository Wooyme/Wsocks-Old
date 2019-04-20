package me.wooy.proxy.server

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.buffer.Buffer
import io.vertx.core.dns.DnsClient
import io.vertx.core.http.HttpServer
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.DecodeException
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.core.net.NetClient
import io.vertx.core.net.NetSocket
import io.vertx.kotlin.core.executeBlockingAwait
import io.vertx.kotlin.core.net.connectAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import me.wooy.proxy.common.UserInfo
import me.wooy.proxy.data.*
import java.net.InetAddress
import java.util.*
import javax.crypto.BadPaddingException
import kotlin.collections.HashSet
import kotlin.collections.LinkedHashMap

class ServerWebSocket : AbstractVerticle() {
  private val logger = LoggerFactory.getLogger(ServerWebSocket::class.java)
  private lateinit var netClient: NetClient
  private lateinit var httpServer: HttpServer
  private val userMap:MutableMap<String,UserInfo> = LinkedHashMap()
  private val hasLoginSet = HashSet<String>()
  private var port: Int = 1888
  private var maxQueueSize = 8*1024*1024
  private val localMap = LinkedHashMap<String, NetSocket>()
  private fun ServerWebSocket.writeBinaryMessageWithOffset(userInfo: UserInfo,data: Buffer) {
    if (userInfo.offset == 0) {
      this.writeBinaryMessage(data)
    } else {
      val bytes = ByteArray(userInfo.offset)
      Random().nextBytes(bytes)
      this.writeBinaryMessage(Buffer.buffer(bytes).appendBuffer(data))
    }
  }

  override fun start(startFuture: Future<Void>) {
    initParams()
    netClient = vertx.createNetClient()
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
  }

  private fun initParams() {
    port = config().getInteger("port")?:1888
    maxQueueSize = config().getInteger("max_queue_size")?:8*1024*1024
    config().getJsonArray("users").forEach {
      val userInfo = UserInfo.fromJson(it as JsonObject)
      userMap[userInfo.secret()] = userInfo
    }
  }

  private fun socketHandler(sock: ServerWebSocket) {
    val userInfo = sock.headers()
        .firstOrNull { userMap.containsKey(it.value) && !hasLoginSet.contains(it.value) }
        ?.let {
          userMap[it.value]
        }?:return sock.reject()
    if(!userInfo.multipleMode){
      hasLoginSet.add(userInfo.secret())
    }
    sock.setWriteQueueMaxSize(maxQueueSize)
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
      hasLoginSet.remove(userInfo.secret())
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
      if(sock.writeQueueFull()){
        net.pause()
        vertx.setTimer(3000){
          net.resume()
        }
      }
      sock.writeBinaryMessageWithOffset(userInfo,RawData.create(userInfo,data.uuid, buffer))
    }.closeHandler {
      localMap.remove(data.uuid)
    }.exceptionHandler {
      it.printStackTrace()
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
}
