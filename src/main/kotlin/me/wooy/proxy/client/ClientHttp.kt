package me.wooy.proxy.client

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.MultiMap
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.*
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.core.net.NetSocket
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import me.wooy.proxy.data.*
import me.wooy.proxy.encryption.Aes
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Deprecated("HTTP写的太乱不再更新，使用Socks5代替HTTP")
class ClientHttp : AbstractVerticle() {
  private val logger = LoggerFactory.getLogger(ClientHttp::class.java)
  private lateinit var httpClient: HttpClient
  private lateinit var httpServer: HttpServer
  private lateinit var ws: WebSocket
  private lateinit var remoteIp: String
  private var remotePort: Int = 0
  private var localPort: Int = 0
  private lateinit var user: String
  private lateinit var pwd: String
  private var offset:Int = 0
  private val tempMap: ConcurrentHashMap<String, HttpServerRequest> = ConcurrentHashMap()
  private val localMap: ConcurrentHashMap<String, NetSocket> = ConcurrentHashMap()
  private fun WebSocket.writeBinaryMessage(offset:Int,data: Buffer){
    if(offset==0){
      this.writeBinaryMessage(data)
    }else{
      val bytes = ByteArray(offset)
      Random().nextBytes(bytes)
      this.writeBinaryMessage(Buffer.buffer(bytes).appendBuffer(data))
    }
  }
  override fun start(startFuture: Future<Void>) {
    httpClient = vertx.createHttpClient()
    httpServer = vertx.createHttpServer()
    remoteIp = config().getString("remote.ip")
    remotePort = config().getInteger("remote.port")
    localPort = config().getInteger("local.port")
    user = config().getString("user")
    pwd = config().getString("pass")
    if(config().containsKey("key")){
      val array = config().getString("key").toByteArray()
      if(16!=array.size)
        Aes.raw = array+ByteArray(16-array.size){ 0x00 }
      else
        Aes.raw = array
    }
    offset = config().getInteger("offset")?:0
    initClient(true, startFuture)
  }

  private fun initServer(future: Future<Void>? = null) {
    httpServer.requestHandler { request ->
      GlobalScope.launch(vertx.dispatcher()) {
        val uuid = UUID.randomUUID().toString()
        val ws = this@ClientHttp.ws
        request.exceptionHandler {
          tempMap.remove(uuid)
          localMap.remove(uuid)
          logger.warn("HTTP Request Exception: ${it.localizedMessage}")
        }
        when (request.method()) {
          HttpMethod.CONNECT -> connectHandler(ws, uuid, request)
          else -> normalRequestHandler(ws, uuid, request)
        }
      }.invokeOnCompletion {
        if(it!=null) logger.warn(it)
      }
    }
    vertx.executeBlocking<HttpServer>({
      httpServer.listen(localPort, it.completer())
    }) {
      logger.info("listen at $localPort")
      future?.complete()
    }
  }

  private fun connectHandler(ws: WebSocket, uuid: String, request: HttpServerRequest) {
    val path = request.path().split(":")
    val port = path[1].toInt()
    val host = path[0]
    tempMap[uuid] = request
    ws.writeBinaryMessage(ClientConnect.create(uuid, host, port).toBuffer())
  }

  private fun normalRequestHandler(ws: WebSocket, uuid: String, request: HttpServerRequest) {
    val data = Buffer.buffer()
    val method = request.method().name
    data.appendString("$method ${request.path()} HTTP/1.1\r\n")
    request.headers().forEach {
      data.appendString("${it.key}: ${it.value}\r\n")
    }
    val host = request.host().split(":")[0]
    val port = request.host().split(":").let { if (it.size == 2) it[1].toInt() else 80 }
    localMap[uuid] = request.netSocket()
    if (request.isEnded || request.getHeader("Content-Length") == null) {
      ws.writeBinaryMessage(HttpData.create(uuid, port, host, data.appendString("\r\n")).toBuffer())
    } else {
      request.bodyHandler { buffer ->
        data.appendBuffer(buffer).appendString("\r\n")
        ws.writeBinaryMessage(HttpData.create(uuid, port, host, data).toBuffer())
      }
    }
  }

  private fun initClient(isFirst: Boolean, future: Future<Void>? = null) {
    if (!isFirst) ws.close()
    val headers = MultiMap.caseInsensitiveMultiMap()
    headers.add("user", user).add("pass", pwd)
    httpClient.websocket(remotePort, remoteIp, "/proxy", headers) { ws ->
      ws.writePing(Buffer.buffer())
      vertx.setPeriodic(5000) {
        ws.writePing(Buffer.buffer())
      }
      ws.binaryMessageHandler { buffer ->
        if (buffer.length() < 4) {
          return@binaryMessageHandler
        }
        when (buffer.getIntLE(0)) {
          Flag.CONNECT_SUCCESS.ordinal -> wsConnectedHandler(ConnectSuccess(buffer).uuid)
          Flag.EXCEPTION.ordinal -> wsExceptionHandler(Exception(buffer))
          Flag.RAW.ordinal -> wsReceivedRawHandler(RawData(buffer))
          else -> logger.warn(buffer.getIntLE(0))
        }
      }.exceptionHandler {
        logger.warn(it)
        ws.close()
        initClient(false)
      }
      this.ws = ws
      vertx.eventBus().publish("status-modify", JsonObject().put("status", remoteIp))
      if (isFirst) {
        initServer(future)
        vertx.eventBus().consumer<JsonObject>("remote-modify") {
          this.remoteIp = it.body().getString("remote.ip")
          this.remotePort = it.body().getInteger("remote.port")
          this.user = it.body().getString("user")
          this.pwd = it.body().getString("pass")
          initClient(false)
        }
      }
    }
  }

  private fun wsConnectedHandler(uuid: String) {
    val request = tempMap.remove(uuid) ?: return
    val netSocket = try {
      request.netSocket()
    } catch (e: IllegalStateException) {
      return
    }
    netSocket.handler { buffer ->
      val rawData = RawData.create(uuid, buffer)
      ws.writeBinaryMessage(offset,rawData.toBuffer())
    }.closeHandler {
      localMap.remove(uuid)
    }
    localMap[uuid] = netSocket
  }

  private fun wsReceivedRawHandler(data: RawData) {
    val netSocket = localMap[data.uuid]?:return
    netSocket.write(data.data)
  }

  private fun wsExceptionHandler(e: Exception) {
    logger.warn("${e.uuid} Get Exception from remote: ${e.message}")
    val netSocket = try{
      localMap.remove(e.uuid) ?: tempMap.remove(e.uuid)?.netSocket()
    }catch (e:java.lang.IllegalStateException){
      return
    }
    netSocket?.end(Buffer.buffer("HTTP/1.1 500 failed\r\n\r\n"))
  }
}
