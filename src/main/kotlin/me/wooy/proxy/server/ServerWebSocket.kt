package me.wooy.proxy.server

import io.vertx.core.AbstractVerticle
import io.vertx.core.DeploymentOptions
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.DeliveryOptions
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
  class KcpWorker(private val kcp:KCP,private val address:String):AbstractVerticle(){
    private val data = ByteArray(1024*1024)
    private val eventBus by lazy { vertx.eventBus() }
    override fun start(){
      super.start()
      vertx.eventBus().localConsumer<Buffer>("$address-input"){
        kcp.Input(it.body().bytes)
      }

      vertx.eventBus().localConsumer<String>("$address-send-wait"){
        it.reply(kcp.WaitSnd()>8000)
      }

      vertx.eventBus().localConsumer<Buffer>("$address-send"){
        kcp.Send(it.body().bytes)
        if(kcp.WaitSnd()>8000){
          it.reply(1)
        }else{
          it.reply(0)
        }
      }
      vertx.setPeriodic(10){
        kcp.Update(Date().time)
        var len = kcp.Recv(data)
        while(len>0){
          eventBus.send("$address-recv",Buffer.buffer(data.sliceArray(0 until len)), DeliveryOptions().setLocalOnly(true))
          len = kcp.Recv(data)
        }
      }
    }

    override fun stop() {
      kcp.clean()
      super.stop()
    }
  }
  class KcpWorkerCaller(private val id:String,private val vertx:Vertx
                        ,private val inputAddress:String
                        ,private val sendAddress:String
                        ,private val recvAddress:String){
    private val eventBus = vertx.eventBus()
    fun input(buf:Buffer){
      eventBus.send(inputAddress,buf, DeliveryOptions().setLocalOnly(true))
    }
    fun send(buf:Buffer,callback:((Int)->Any)?=null){
      if(callback==null){
        eventBus.send(sendAddress,buf, DeliveryOptions().setLocalOnly(true))
      }else {
        eventBus.send<Int>(sendAddress, buf, DeliveryOptions().setLocalOnly(true)) {
          callback(it.result().body())
        }
      }
    }

    fun getWaiting(callback: (Boolean) -> Any){
      eventBus.send<Boolean>("$sendAddress-wait","",DeliveryOptions().setLocalOnly(true)){
        callback(it.result().body())
      }
    }

    fun recv(callback:(Buffer)->Any){
      eventBus.localConsumer<Buffer>(recvAddress){
        callback(it.body())
      }
    }

    fun stop(){
      vertx.undeploy(id)
    }
  }
  private val logger = LoggerFactory.getLogger(ServerWebSocket::class.java)
  private lateinit var netClient: NetClient
  private lateinit var httpServer: HttpServer
  private val userMap:MutableMap<String,UserInfo> = LinkedHashMap()
  private val hasLoginMap = HashMap<String,Int>()
  //web 服务器端口
  private val port = 8888
  private val localMap = LinkedHashMap<String, NetSocket>()
  private val ds by lazy { vertx.createDatagramSocket() }
  private val fakeIp by lazy { config().getString("fake_ip") }
  private val fakePort by lazy { config().getInteger("fake_port") }
  private val udpPort by lazy { config().getInteger("udp_port") }
  private val registeredRandomKey = HashMap<String,UserInfo>()
  private val senderMap:MutableMap<String,String> = LinkedHashMap()
  private val kcpMap:MutableMap<String,KcpWorkerCaller> = LinkedHashMap()

  private fun SocketAddress.getString() = this.host()+this.port()

  private fun KcpWorkerCaller.sendWithOffset(userInfo: UserInfo,data: Buffer,callback:((Int)->Any)?=null){
    if (userInfo.offset == 0) {
      this.send(data,callback)
    } else {
      val bytes = ByteArray(userInfo.offset)
      Random().nextBytes(bytes)
      this.send(Buffer.buffer(bytes).appendBuffer(data),callback)
    }
  }

  override fun start(startFuture: Future<Void>) {
    initParams()
    netClient = vertx.createNetClient(NetClientOptions().setReceiveBufferSize(4*1024*1024))
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
    registeredRandomKey[sock.path()] = userInfo
    sock.closeHandler {
      hasLoginMap[userInfo.secret()] = hasLoginMap[userInfo.secret()]?.minus(1)?:0
      kcpMap.remove(senderMap.remove(sock.path()))?.stop()
    }
    sock.accept()
  }

  private suspend fun clientConnectHandler(userInfo: UserInfo,kcp:KcpWorkerCaller, data: ClientConnect) {
    val net = try {
      netClient.connectAwait(data.port, InetAddress.getByName(data.host).hostAddress)
    } catch (e: Throwable) {
      logger.warn(e.message)
      kcp.sendWithOffset(userInfo,Exception.create(userInfo,data.uuid, e.localizedMessage))
      return
    }
    net.handler { buffer ->
      fun wait(){
        net.pause()
        vertx.setTimer(3000){
          kcp.getWaiting {
            if(it) wait() else net.resume()
          }
        }
      }
      kcp.sendWithOffset(userInfo,RawData.create(userInfo,data.uuid, buffer)){
        if(it==1){
          wait()
        }
      }
    }.closeHandler {
      kcp.sendWithOffset(userInfo,Exception.create(userInfo,data.uuid,""))
      localMap.remove(data.uuid)
    }.exceptionHandler {
      localMap.remove(data.uuid)?.close()
    }
    localMap[data.uuid] = net
    kcp.sendWithOffset(userInfo,ConnectSuccess.create(userInfo,data.uuid))
  }

  private fun clientRawHandler(userInfo: UserInfo,kcp:KcpWorkerCaller, data: RawData) {
    val net = localMap[data.uuid]
    net?.write(data.data) ?: let {
      kcp.sendWithOffset(userInfo,Exception.create(userInfo,data.uuid, "Remote socket has closed"))
    }
  }

  private fun clientDNSHandler(userInfo: UserInfo,kcp:KcpWorkerCaller, data: DnsQuery) {
    vertx.executeBlocking<String>({
      val address = try{
        InetAddress.getByName(data.host)?.hostAddress?:"0.0.0.0"
      }catch(e:Throwable){
        "0.0.0.0"
      }
      it.complete(address)
    }){
      kcp.sendWithOffset(userInfo,DnsQuery.create(userInfo,data.uuid, it.result()))
    }
  }

  private fun initKcp(){
    ds.handler {
      if(it.data().length()>10 && it.data().getString(0,5)=="login"){
        if(kcpMap.containsKey(it.sender().getString())) return@handler
        val randomKey = it.data().getString(5,it.data().length())
        val userInfo = registeredRandomKey[randomKey]?:return@handler
        logger.info("IP:${it.sender().host()},Port:${it.sender().port()}")
        val conv = ByteBuffer.wrap(randomKey.toByteArray()).getInt(0).toLong()
        val raw = Kcp()
        val result = raw.init(fakeIp,fakePort,udpPort,it.sender().host(),it.sender().port())
        val kcp = object : KCP(conv) {
          override fun output(buffer: ByteArray, size: Int) {
            raw.sendBuf(result,buffer,size)
          }
        }
        kcp.WndSize(256,256)
        kcp.NoDelay(1, 10, 2, 1)

        vertx.deployVerticle(KcpWorker(kcp,randomKey), DeploymentOptions().setWorker(true)){aResult->
          if(aResult.failed()){
            aResult.cause().printStackTrace()
            return@deployVerticle
          }
          val kcpWC = KcpWorkerCaller(aResult.result(),vertx,"$randomKey-input"
              ,"$randomKey-send","$randomKey-recv")
          kcpWC.recv {_buffer->
            GlobalScope.launch(vertx.dispatcher()) {
              val buffer = if (userInfo.offset != 0) _buffer.getBuffer(userInfo.offset, _buffer.length()) else _buffer
              try {
                when (buffer.getIntLE(0)) {
                  Flag.CONNECT.ordinal -> clientConnectHandler(userInfo,kcpWC, ClientConnect(userInfo,buffer))
                  Flag.RAW.ordinal -> clientRawHandler(userInfo,kcpWC, RawData(userInfo,buffer))
                  Flag.DNS.ordinal -> clientDNSHandler(userInfo,kcpWC, DnsQuery(userInfo,buffer))
                  else -> logger.error(buffer.getIntLE(0))
                }
              } catch (e: BadPaddingException) {

              } catch(e:DecodeException){

              }
            }
          }
          kcpMap[it.sender().getString()] = kcpWC
          senderMap[randomKey] = it.sender().getString()
        }
      }else {
        kcpMap[it.sender().getString()]?.input(it.data())
      }
    }.listen(udpPort,"0.0.0.0"){
      if(it.succeeded())
        logger.info("Listen at $udpPort")
      else{
        it.cause().printStackTrace()
        System.exit(-1)
      }
    }
  }
}
