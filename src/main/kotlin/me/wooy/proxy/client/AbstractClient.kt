package me.wooy.proxy.client

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.MultiMap
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.WebSocket
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.Logger
import io.vertx.core.net.NetClient
import me.wooy.proxy.data.*
import me.wooy.proxy.encryption.Aes
import org.apache.commons.codec.digest.DigestUtils
import java.util.*

abstract class AbstractClient : AbstractVerticle() {
  abstract val logger: Logger
  private var bufferSizeHistory: Long = 0L
  protected var port = 1080
  private var remotePort = 1888
  private lateinit var remoteIp: String
  private var offset: Int = 0
  private var useGFWList: Boolean = false
  private lateinit var user: String
  private lateinit var pass: String
  private val httpClient: HttpClient by lazy { vertx.createHttpClient() }
  protected lateinit var ws: WebSocket
  protected val localNetClient: NetClient by lazy { vertx.createNetClient() }
  protected fun WebSocket.writeBinaryMessageWithOffset(data: Buffer) {
    if (offset == 0) {
      this.writeBinaryMessage(data)
    } else {
      val bytes = ByteArray(offset)
      Random().nextBytes(bytes)
      this.writeBinaryMessage(Buffer.buffer(bytes).appendBuffer(data))
    }
  }

  override fun start(future: Future<Void>) {
    if (config().getBoolean("ui") != false) {
      vertx.eventBus().consumer<JsonObject>("config-modify") {
        readConfig(it.body())
        login()
        initLocalServer()
      }
    } else {
      readConfig(config())
      login()
      initLocalServer()
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

  private fun readConfig(json: JsonObject) {
    port = json.getInteger("local.port") ?: 1080
    remotePort = json.getInteger("remote.port") ?: 1888
    json.getString("remote.ip")?.let { remoteIp = it }
    useGFWList = json.getBoolean("gfw.use") ?: false
    json.getString("user")?.let { user = it }
    json.getString("pass")?.let { pass = it }

    offset = json.getInteger("offset") ?: 0
    if (json.containsKey("key")) {
      val array = json.getString("key").toByteArray()
      if (16 > array.size)
        Aes.raw = array + ByteArray(16 - array.size) { 0x06 }
      else
        Aes.raw = array
    }
    if (json.containsKey("gfw.path")) GFWRegex.initRegexList(json.getString("gfw.path"))
  }

  private fun login() {
    if(!this::remoteIp.isInitialized)
      return
    httpClient.websocket(remotePort
        , remoteIp
        , "/proxy"
        , MultiMap.caseInsensitiveMultiMap()
        .add("user", user).add("pass", pass).add("md5",DigestUtils.md5Hex(Aes.raw))) { webSocket ->
      webSocket.writePing(Buffer.buffer())
      webSocket.binaryMessageHandler { _buffer ->
        if (_buffer.length() < 4) {
          return@binaryMessageHandler
        }
        val buffer = if(offset!=0) _buffer.getBuffer(offset,_buffer.length()) else _buffer
        when (buffer.getIntLE(0)) {
          Flag.CONNECT_SUCCESS.ordinal -> wsConnectedHandler(ConnectSuccess(buffer).uuid)
          Flag.EXCEPTION.ordinal -> wsExceptionHandler(Exception(buffer))
          Flag.RAW.ordinal -> {
            bufferSizeHistory += buffer.length()
            wsReceivedRawHandler(RawData(buffer))
          }
          Flag.UDP.ordinal -> {
            bufferSizeHistory += buffer.length()
            wsReceivedUDPHandler(RawUDPData(buffer))
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
  }

  protected fun isWebSocketAvailable() = this::ws.isInitialized

  protected fun throughProxy(host:String) = !useGFWList || GFWRegex.doNeedProxy(host)

  abstract fun initLocalServer()
  abstract fun wsConnectedHandler(uuid: String)
  abstract fun wsExceptionHandler(e: Exception)
  abstract fun wsReceivedRawHandler(data: RawData)
  protected open fun wsReceivedUDPHandler(data: RawUDPData){}


}