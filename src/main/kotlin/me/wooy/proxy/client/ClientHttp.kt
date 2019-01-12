package me.wooy.proxy.client

import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.logging.Logger
import io.vertx.core.logging.LoggerFactory
import io.vertx.core.net.NetSocket
import me.wooy.proxy.data.ClientConnect
import me.wooy.proxy.data.Exception
import me.wooy.proxy.data.HttpData
import me.wooy.proxy.data.RawData
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class ClientHttp : AbstractClient() {
  override val logger: Logger = LoggerFactory.getLogger(ClientHttp::class.java)
  private lateinit var httpServer: HttpServer
  private val requestMap: ConcurrentHashMap<String, HttpServerRequest> = ConcurrentHashMap()
  private val socketMap: ConcurrentHashMap<String, NetSocket> = ConcurrentHashMap()

  override fun initLocalServer() {
    initServer()
  }

  override fun stop() {
    httpServer.close()
    super.stop()
  }

  private fun initServer() {
    if (this::httpServer.isInitialized) {
      requestMap.forEach {
        it.value.response().setStatusCode(500).end()
      }
      socketMap.forEach {
        it.value.close()
      }
      httpServer.close()
    }
    httpServer = vertx.createHttpServer().requestHandler { request ->
      if (!this.isWebSocketAvailable()) {
        request.response().setStatusCode(500).end()
        return@requestHandler
      }
      val uuid = UUID.randomUUID().toString()
      request.exceptionHandler {
        requestMap.remove(uuid)?.response()?.end()
        socketMap.remove(uuid)?.close()
        logger.warn("HTTP Request Exception: ${it.localizedMessage}")
      }
      when (request.method()) {
        HttpMethod.CONNECT -> tryConnect(uuid, request)
        else -> sampleRequestHandler(uuid, request)
      }
    }
    vertx.executeBlocking<HttpServer>({
      httpServer.listen(port, it.completer())
    }) {
      logger.info("listen at $port")
    }
  }

  private fun tryConnect(uuid: String, request: HttpServerRequest) {
    val path = request.path().split(":")
    val port = if(path.size==2) path[1].toInt() else 80
    val host = path[0]
    requestMap[uuid] = request
    ws.writeBinaryMessageWithOffset(ClientConnect.create(uuid, host, port).toBuffer())
  }

  private fun sampleRequestHandler(uuid: String, request: HttpServerRequest) {
    val data = Buffer.buffer()
    val method = request.method().name
    data.appendString("$method ${request.path()} HTTP/1.1\r\n")
    request.headers().forEach {
      data.appendString("${it.key}: ${it.value}\r\n")
    }
    val host = request.host().split(":")[0]
    val port = request.host().split(":").let { if (it.size == 2) it[1].toInt() else 80 }
    socketMap[uuid] = request.netSocket()
    if (request.isEnded || request.getHeader("Content-Length") == null) {
      ws.writeBinaryMessageWithOffset(HttpData.create(uuid, port, host, data.appendString("\r\n")).toBuffer())
    } else {
      request.bodyHandler { buffer ->
        data.appendBuffer(buffer).appendString("\r\n")
        ws.writeBinaryMessageWithOffset(HttpData.create(uuid, port, host, data).toBuffer())
      }
    }
  }

  override fun wsConnectedHandler(uuid: String) {
    val request = requestMap.remove(uuid) ?: return
    val netSocket = try {
      request.netSocket()
    } catch (e: IllegalStateException) {
      return
    }
    netSocket.handler { buffer ->
      val rawData = RawData.create(uuid, buffer)
      ws.writeBinaryMessageWithOffset(rawData.toBuffer())
    }.closeHandler {
      socketMap.remove(uuid)
    }
    socketMap[uuid] = netSocket
  }

  override fun wsReceivedRawHandler(data: RawData) {
    val netSocket = socketMap[data.uuid] ?: return
    netSocket.write(data.data)
  }

  override fun wsExceptionHandler(e: Exception) {
    logger.warn("${e.uuid} Get Exception from remote: ${e.message}")
    val netSocket = try {
      socketMap.remove(e.uuid) ?: requestMap.remove(e.uuid)?.netSocket()
    } catch (e: java.lang.IllegalStateException) {
      return
    }
    netSocket?.end(Buffer.buffer("HTTP/1.1 500 failed\r\n\r\n"))
  }
}
