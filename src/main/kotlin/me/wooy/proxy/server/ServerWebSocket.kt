package me.wooy.proxy.server

import io.vertx.core.AbstractVerticle
import io.vertx.core.DeploymentOptions
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.eventbus.EventBus
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
import java.nio.ByteBuffer
import java.util.*
import javax.crypto.BadPaddingException
import kotlin.collections.LinkedHashMap

class ServerWebSocket : AbstractVerticle() {
  class KcpWorker(private val kcp: KCP, private val address: String) : AbstractVerticle() {
    override fun start() {
      super.start()
      vertx.eventBus().localConsumer<Buffer>("$address-input") {
        kcp.Input(it.body().bytes)
      }
      vertx.eventBus().localConsumer<Buffer>("$address-send") {
        kcp.Send(it.body().bytes)
        it.reply(kcp.WaitSnd() > 8000)
      }
      vertx.eventBus().localConsumer<Int>("$address-send-wait") {
        it.reply(kcp.WaitSnd() > 8000)
      }
      vertx.setPeriodic(10) {
        kcp.Update(Date().time)
      }
    }

    override fun stop() {
      kcp.clean()
      super.stop()
    }
  }

  class KcpWorkerCaller(private val id: String, private val vertx: Vertx, private val eventBus: EventBus, private val inputAddress: String, private val sendAddress: String) {
    fun input(buf: Buffer) {
      eventBus.send(inputAddress, buf, DeliveryOptions().setLocalOnly(true))
    }

    fun send(buf: Buffer, callback: ((Boolean) -> Any)? = null) {
      if (callback == null) {
        eventBus.send(sendAddress, buf, DeliveryOptions().setLocalOnly(true))
      } else {
        eventBus.send<Boolean>(sendAddress, buf, DeliveryOptions().setLocalOnly(true)) {
          callback(it.result().body())
        }
      }
    }

    fun shouldWait(callback: (Boolean) -> Any) {
      eventBus.send<Boolean>("$sendAddress-wait", 0, DeliveryOptions().setLocalOnly(true)) {
        callback(it.result().body())
      }
    }

    fun stop() {
      vertx.undeploy(id)
    }
  }

  private val logger = LoggerFactory.getLogger(ServerWebSocket::class.java)
  private lateinit var netClient: NetClient
  private lateinit var httpServer: HttpServer
  private val userMap: MutableMap<String, UserInfo> = LinkedHashMap()
  private val hasLoginMap = HashMap<String, Int>()
  //web 服务器端口
  private val port = 8888
  private val localMap = LinkedHashMap<String, LinkedHashMap<String, NetSocket>>()
  private val ds by lazy { vertx.createDatagramSocket() }
  private val fakeTcpServer by lazy { vertx.createNetServer() }
  private val fakeIp by lazy { config().getString("fake_ip") }
  private val fakePort by lazy { config().getInteger("fake_port") }
  private val udpPort by lazy { config().getInteger("udp_port") }
  private val senderMap: MutableMap<String, String> = LinkedHashMap()
  private val kcpMap: MutableMap<String, KcpWorkerCaller> = LinkedHashMap()

  private fun SocketAddress.getString() = this.host() + this.port()

  private fun KcpWorkerCaller.sendWithOffset(userInfo: UserInfo, data: Buffer, callback: ((Boolean) -> Any)? = null) {
    if (userInfo.offset == 0) {
      this.send(data, callback)
    } else {
      val bytes = ByteArray(userInfo.offset)
      Random().nextBytes(bytes)
      this.send(Buffer.buffer(bytes).appendBuffer(data), callback)
    }
  }

  override fun start(startFuture: Future<Void>) {
    initParams()
    netClient = vertx.createNetClient(NetClientOptions().setReceiveBufferSize(4 * 1024))
    httpServer = vertx.createHttpServer()
    httpServer.websocketHandler(this::socketHandler)
    vertx.executeBlocking<HttpServer>({
      httpServer.listen(port, it.completer())
    }) {
      logger.info("Proxy server listen at $port")
      startFuture.complete()
    }
    vertx.eventBus().consumer<JsonObject>("user-modify") {
      val userInfo = UserInfo.fromJson(it.body())
      userMap[userInfo.secret()] = userInfo
    }
    vertx.eventBus().consumer<JsonObject>("user-remove") {
      val username = it.body().getString("user")
      userMap.asIterable().forEach { v ->
        if (v.value.username == username) {
          userMap.remove(v.key)
          return@consumer
        }
      }
    }
    initRawTcpKcp()
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
    val userInfo = userMap[key] ?: return sock.reject()
    if (hasLoginMap[key]?.let {
          userInfo.maxLoginDevices in 0..(it - 1)
        } == true) {
      return sock.reject()
    }
    hasLoginMap[key] = (hasLoginMap[key] ?: 0) + 1
    localMap[sock.path()] = LinkedHashMap()
    sock.binaryMessageHandler { _buffer ->
      GlobalScope.launch(vertx.dispatcher()) {
        val buffer = if (userInfo.offset != 0) _buffer.getBuffer(userInfo.offset, _buffer.length()) else _buffer
        try {
          when (buffer.getIntLE(0)) {
            Flag.CONNECT.ordinal -> clientConnectHandler(userInfo, sock, ClientConnect(userInfo, buffer))
            Flag.RAW.ordinal -> clientRawHandler(userInfo, sock, RawData(userInfo, buffer))
            Flag.DNS.ordinal -> clientDNSHandler(userInfo, sock, DnsQuery(userInfo, buffer))
            Flag.EXCEPTION.ordinal -> clientExceptionHandler(sock, Exception(userInfo, buffer))
            Flag.KCP.ordinal -> clientKcpHandler(sock, buffer)
            else -> logger.error(buffer.getIntLE(0))
          }
        } catch (e: BadPaddingException) {
          sock.reject()
        } catch (e: DecodeException) {
        }
      }
    }.closeHandler {
      hasLoginMap[userInfo.secret()] = hasLoginMap[userInfo.secret()]?.minus(1) ?: 0
      localMap[sock.path()]?.forEach {
        it.value.close()
      }
      kcpMap.remove(senderMap.remove(sock.path()))?.stop()
    }
    sock.accept()
  }

  private suspend fun clientConnectHandler(userInfo: UserInfo, sock: ServerWebSocket, data: ClientConnect) {
    val kcp = kcpMap[senderMap[sock.path()]] ?: return
    val net = try {
      netClient.connectAwait(data.port, InetAddress.getByName(data.host).hostAddress)
    } catch (e: Throwable) {
      logger.warn(e.message)
      kcp.sendWithOffset(userInfo, Exception.create(userInfo, data.uuid, e.localizedMessage))
      return
    }
    net.handler { buffer ->
      fun wait() {
        net.pause()
        vertx.setTimer(500) {
          kcp.shouldWait {
            if (it) wait() else net.resume()
          }
        }
      }
      kcp.sendWithOffset(userInfo, RawData.create(userInfo, data.uuid, buffer)) {
        if (it) wait()
      }
    }.closeHandler {
      kcp.sendWithOffset(userInfo, Exception.create(userInfo, data.uuid, ""))
      localMap[sock.path()]?.remove(data.uuid)
    }.exceptionHandler {
      localMap[sock.path()]?.remove(data.uuid)?.close()
    }
    localMap[sock.path()]?.put(data.uuid, net)
    kcp.sendWithOffset(userInfo, ConnectSuccess.create(userInfo, data.uuid))
  }

  private fun clientRawHandler(userInfo: UserInfo, sock: ServerWebSocket, data: RawData) {
    val net = localMap[sock.path()]?.get(data.uuid)
    net?.write(data.data) ?: let {
      kcpMap[senderMap[sock.path()]]?.sendWithOffset(userInfo, Exception.create(userInfo, data.uuid, "Remote socket has closed"))
    }
  }

  private fun clientDNSHandler(userInfo: UserInfo, sock: ServerWebSocket, data: DnsQuery) {
    vertx.executeBlocking<String>({
      val address = try {
        InetAddress.getByName(data.host)?.hostAddress ?: "0.0.0.0"
      } catch (e: Throwable) {
        "0.0.0.0"
      }
      it.complete(address)
    }) {
      kcpMap[senderMap[sock.path()]]?.sendWithOffset(userInfo, DnsQuery.create(userInfo, data.uuid, it.result()))
    }
  }

  private fun clientExceptionHandler(sock: ServerWebSocket, data: Exception) {
    if (data.message.isEmpty()) localMap[sock.path()]?.remove(data.uuid)?.close()
  }

  private fun clientKcpHandler(sock: ServerWebSocket, data: Buffer) {
    kcpMap[senderMap[sock.path()]]?.input(data.getBuffer(Int.SIZE_BYTES, data.length()))
  }

  private fun initRawTcpKcp(){
    fakeTcpServer.connectHandler {
      logger.info("Connect ${it.remoteAddress().host()}:${it.remoteAddress().port()}")
      it.handler {buf->
        if (buf.getString(0, 5) == "login") {
          if (kcpMap.containsKey(it.remoteAddress().getString())) return@handler
          val randomKey = buf.getString(5, buf.length())
          println("IP:${it.remoteAddress().host()},Port:${it.remoteAddress().port()}")
          println(ByteBuffer.wrap(randomKey.toByteArray()).getLong(0))
          val conv = ByteBuffer.wrap(randomKey.toByteArray()).getInt(0).toLong()
          val raw = Kcp()
          val result = raw.init(fakeIp, fakePort, fakePort, it.remoteAddress().host(), it.remoteAddress().port())
          val kcp = object : KCP(conv) {
              override fun output(buffer: ByteArray, size: Int) {
                raw.sendBuf(result, buffer, size)
              }
            }
          kcp.SetMtu(1000)
          kcp.WndSize(128, 128)
          kcp.NoDelay(1, 10, 2, 1)
          vertx.deployVerticle(KcpWorker(kcp, randomKey), DeploymentOptions().setWorker(true)) { result ->
            if (result.failed()) {
              result.cause().printStackTrace()
              return@deployVerticle
            }
            kcpMap[it.remoteAddress().getString()] = KcpWorkerCaller(result.result(), vertx, vertx.eventBus(), "$randomKey-input"
                , "$randomKey-send")
            senderMap[randomKey] = it.remoteAddress().getString()
          }
          it.pause()
        }
      }.closeHandler {

      }
    }.listen(fakePort){
      if(it.failed()){
        it.cause().printStackTrace()
        System.exit(-1)
      }
      logger.info("Listen at $fakePort")
    }
  }
}
