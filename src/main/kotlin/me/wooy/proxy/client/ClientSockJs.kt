package me.wooy.proxy.client

import io.vertx.core.AbstractVerticle
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.*
import io.vertx.core.logging.LoggerFactory
import io.vertx.core.net.NetSocket
import io.vertx.kotlin.coroutines.awaitEvent
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import me.wooy.proxy.data.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class ClientSockJs:AbstractVerticle() {
  private val logger = LoggerFactory.getLogger(ClientSockJs::class.java)
  private lateinit var httpClient: HttpClient
  private lateinit var httpServer: HttpServer
  private lateinit var ws:WebSocket
  private lateinit var remoteIp:String
  private val tempMap:ConcurrentHashMap<String,HttpServerRequest> = ConcurrentHashMap()
  private val localMap:ConcurrentHashMap<String,NetSocket> = ConcurrentHashMap()
  override fun start() {
    httpClient = vertx.createHttpClient()
    httpServer = vertx.createHttpServer()
    remoteIp = config().getString("ip")
    initClient()
    initServer()
    super.start()
  }

  private fun initServer(){
    httpServer.connectionHandler {connection->
      logger.info("Take connection from ${connection.remoteAddress()}")
      connection.closeHandler {
        logger.info("Connection closed by ${connection.remoteAddress()}")
      }
    }.requestHandler {
      GlobalScope.launch(vertx.dispatcher()) {
        val uuid = UUID.randomUUID().toString()
        val ws = this@ClientSockJs.ws
        when (it.method()) {
          HttpMethod.CONNECT -> connectHandler(ws,uuid, it)
          else -> normalRequestHandler(ws,uuid, it)
        }
      }.invokeOnCompletion {
        it?.printStackTrace()
      }
    }
    vertx.executeBlocking<HttpServer>({
      httpServer.listen(2888,it.completer())
    }){
      logger.info("Client listen at 2888")
    }
  }

  private fun connectHandler(ws:WebSocket,uuid: String,request: HttpServerRequest){
    val path = request.path().split(":")
    val port = path[1].toInt()
    val host = path[0]
    tempMap[uuid] = request
    ws.writeBinaryMessage(ClientConnect.create(uuid,host,port).toBuffer())
  }

  private fun normalRequestHandler(ws:WebSocket,uuid: String,request: HttpServerRequest){
    val data=Buffer.buffer()
    val method = request.method().name
    data.appendString("$method ${request.path()} HTTP/1.1\r\n")
    request.headers().forEach {
      data.appendString("${it.key}: ${it.value}\r\n")
    }
    val host = request.host().split(":")[0]
    val port = request.host().split(":").let { if(it.size==2) it[1].toInt() else 80 }
    localMap[uuid] = request.netSocket()
    if(request.isEnded ||request.getHeader("Content-Length")==null){
      ws.writeBinaryMessage(HttpData.create(uuid,port,host,data.appendString("\r\n")).toBuffer())
    }else {
      request.bodyHandler { buffer ->
        data.appendBuffer(buffer).appendString("\r\n")
        ws.writeBinaryMessage(HttpData.create(uuid, port, host, data).toBuffer())
      }
    }
  }

  private suspend fun getWS():WebSocket{
    return awaitEvent {handler->
      httpClient.websocket(1888,remoteIp,"/proxy") { ws ->
        ws.writePing(Buffer.buffer())
        vertx.setPeriodic(5000){
          ws.writePing(Buffer.buffer())
        }
        ws.binaryMessageHandler { buffer ->
          when(buffer.getIntLE(0)){
            Flag.CONNECT_SUCCESS.ordinal->wsConnectedHandler(ws,ConnectSuccess(buffer).uuid)
            Flag.EXCEPTION.ordinal-> wsExceptionHandler(Exception(buffer))
            Flag.RAW.ordinal -> wsReceivedRawHandler(RawData(buffer))
            else->logger.warn(buffer.getIntLE(0))
          }
        }.exceptionHandler {
          logger.warn(it.localizedMessage)
        }
        handler.handle(ws)
      }
    }
  }

  private fun initClient(){
    httpClient.websocket(1888,remoteIp,"/proxy"){ws->
      ws.writePing(Buffer.buffer())
      vertx.setPeriodic(5000){
        ws.writePing(Buffer.buffer())
      }
      ws.binaryMessageHandler { buffer ->
        when(buffer.getIntLE(0)){
          Flag.CONNECT_SUCCESS.ordinal->wsConnectedHandler(ws,ConnectSuccess(buffer).uuid)
          Flag.EXCEPTION.ordinal-> wsExceptionHandler(Exception(buffer))
          Flag.RAW.ordinal -> wsReceivedRawHandler(RawData(buffer))
          else->logger.warn(buffer.getIntLE(0))
        }
      }.exceptionHandler {
        it.printStackTrace()
      }.closeHandler {
        //如果ws意外关闭则重建
        initClient()
      }
      this.ws = ws
    }
  }

  private fun wsConnectedHandler(ws:WebSocket,uuid:String){
    val request = tempMap.remove(uuid)!!
    val netSocket = request.netSocket()
    netSocket.handler {buffer->
      val rawData = RawData.create(uuid,buffer)
      ws.writeBinaryMessage(rawData.toBuffer())
    }.closeHandler {
      localMap.remove(uuid)
    }
    localMap[uuid] = netSocket
  }

  private fun wsReceivedRawHandler(data: RawData){
    val netSocket = localMap[data.uuid]
    netSocket?.write(data.data)
  }

  private fun wsExceptionHandler(e:Exception){
    val netSocket = localMap.remove(e.uuid)?:tempMap.remove(e.uuid)!!.netSocket()
    netSocket.end(Buffer.buffer("HTTP/1.1 500 failed\r\n\r\n"))
  }
}
