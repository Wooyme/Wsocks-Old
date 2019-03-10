package me.wooy.proxy.client

import io.vertx.core.buffer.Buffer
import io.vertx.core.datagram.DatagramPacket
import io.vertx.core.datagram.DatagramSocket
import io.vertx.core.logging.Logger
import io.vertx.core.logging.LoggerFactory
import io.vertx.core.net.NetServer
import io.vertx.core.net.NetSocket
import io.vertx.core.net.SocketAddress
import me.wooy.proxy.data.ClientConnect
import me.wooy.proxy.data.Exception
import me.wooy.proxy.data.RawData
import me.wooy.proxy.data.RawUDPData
import java.net.Inet4Address
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class ClientSocks5 : AbstractClient() {
  override val logger: Logger = LoggerFactory.getLogger(ClientSocks5::class.java)
  private lateinit var udpServer: DatagramSocket
  private lateinit var netServer: NetServer
  private val connectMap = ConcurrentHashMap<String, NetSocket>()
  private val senderMap = ConcurrentHashMap<String, SocketAddress>()
  private val address = Inet4Address.getByName("127.0.0.1").address

  override fun initLocalServer() {
    initSocksServer(port)
    initUdpServer()
  }

  override fun stop() {
    senderMap.clear()
    udpServer.close()
    netServer.close()
    super.stop()
  }

  private fun initSocksServer(port: Int) {
    if (this::netServer.isInitialized) {
      this.netServer.close()
    }
    this.netServer = vertx.createNetServer().connectHandler { socket ->
      if (!this.isWebSocketAvailable()) {
        socket.close()
        return@connectHandler
      }
      val uuid = UUID.randomUUID().toString()
      socket.handler {
        bufferHandler(uuid, socket, it)
      }.closeHandler {
        connectMap.remove(uuid)
      }
    }.listen(port) {
      logger.info("Listen at $port")
    }
  }

  private fun initUdpServer() {
    if(this::udpServer.isInitialized){
      senderMap.clear()
      this.udpServer.close()
    }
    udpServer = vertx.createDatagramSocket().handler {
      if (!this.isWebSocketAvailable())
        return@handler
      udpPacketHandler(it)
    }.listen(29799, "127.0.0.1") {
      logger.info("UDP Server listen at 29799")
    }
  }

  private fun bufferHandler(uuid: String, netSocket: NetSocket, buffer: Buffer) {
    val version = buffer.getByte(0)
    if (version != 0x05.toByte()) {
      netSocket.close()
    }
    when {
      buffer.getByte(1).toInt() + 2 == buffer.length() -> {
        handshakeHandler(netSocket)
      }
      else -> requestHandler(uuid, netSocket, buffer)
    }
  }

  private fun handshakeHandler(netSocket: NetSocket) {
    netSocket.write(Buffer.buffer().appendByte(0x05.toByte()).appendByte(0x00.toByte()))
  }

  private fun requestHandler(uuid: String, netSocket: NetSocket, buffer: Buffer) {
    /*
    * |VER|CMD|RSV|ATYP|DST.ADDR|DST.PORT|
    * -----------------------------------------
    * | 1 | 1 |0x0| 1  |Variable|   2    |
    * -----------------------------------------
    * */
    val cmd = buffer.getByte(1)
    val addressType = buffer.getByte(3)
    logger.info("UUID:$uuid,$cmd,$addressType")
    val (host, port) = when (addressType) {
      0x01.toByte() -> {
        val host = Inet4Address.getByAddress(buffer.getBytes(4, 8)).toString().removePrefix("/")
        val port = buffer.getShort(8).toInt()
        logger.info("UUID:$uuid,Connect to $host:$port")
        host to port
      }
      0x03.toByte() -> {
        val hostLen = buffer.getByte(4).toInt()
        val host = buffer.getString(5, 5 + hostLen)
        val port = buffer.getShort(5 + hostLen).toInt()
        host to port
      }
      else -> {
        netSocket.write(Buffer.buffer()
            .appendByte(0x05.toByte())
            .appendByte(0x08.toByte()))
        return
      }
    }
    when (cmd) {
      0x01.toByte() -> {
        tryConnect(uuid, netSocket, host, port)
      }
      0x03.toByte() -> {
        netSocket.write(Buffer.buffer()
            .appendByte(0x05.toByte())
            .appendByte(0x00.toByte())
            .appendByte(0x00.toByte())
            .appendByte(0x01.toByte())
            .appendBytes(address).appendUnsignedShortLE(29799))
      }
      else -> {
        netSocket.write(Buffer.buffer()
            .appendByte(0x05.toByte())
            .appendByte(0x07.toByte()))
        return
      }
    }
  }

  private fun udpPacketHandler(packet: DatagramPacket) {
    val buffer = packet.data()
    if (buffer.getByte(0) != 0x0.toByte() || buffer.getByte(1) != 0x0.toByte()) {
      return
    }
    if (buffer.getByte(2) != 0x0.toByte()) {
      return
    }
    val addressType = buffer.getByte(3)
    val (host, port, data) = when (addressType) {
      0x01.toByte() -> {
        val host = Inet4Address.getByAddress(buffer.getBytes(5, 9)).toString()
        val port = buffer.getShort(9).toInt()
        val data = buffer.getBuffer(11, buffer.length())
        Triple(host, port, data)
      }
      0x03.toByte() -> {
        val hostLen = buffer.getByte(4).toInt()
        val host = buffer.getString(5, 5 + hostLen)
        val port = buffer.getShort(5 + hostLen).toInt()
        val data = buffer.getBuffer(7 + hostLen, buffer.length())
        Triple(host, port, data)
      }
      else -> return
    }
    val uuid = UUID.randomUUID().toString()
    senderMap[uuid] = packet.sender()
    ws.writeBinaryMessageWithOffset(RawUDPData.create(uuid, host, port, data).toBuffer())
  }

  private fun tryConnect(uuid: String, netSocket: NetSocket, host: String, port: Int) {
    if (!throughProxy(host)) {
      localNetClient.connect(port, host) {
        if (it.failed()) {
          netSocket.close()
          return@connect
        }
        val pipe = it.result()
        pipe.handler { buffer ->
          netSocket.write(buffer)
        }.closeHandler {
          netSocket.close()
        }
        netSocket.handler { buffer ->
          pipe.write(buffer)
        }.closeHandler {
          pipe.close()
        }
        val buffer = Buffer.buffer()
            .appendByte(0x05.toByte())
            .appendByte(0x00.toByte())
            .appendByte(0x00.toByte())
            .appendByte(0x01.toByte())
            .appendBytes(ByteArray(6) { 0x0 })
        netSocket.write(buffer)
      }
    } else {
      connectMap[uuid] = netSocket
      ws.writeBinaryMessageWithOffset(ClientConnect.create(uuid, host, port).toBuffer())
    }
  }

  override fun wsConnectedHandler(uuid: String) {
    val netSocket = connectMap[uuid] ?: return
    //建立连接后修改handler
    netSocket.handler {
      ws.writeBinaryMessageWithOffset(RawData.create(uuid, it).toBuffer())
    }
    val buffer = Buffer.buffer()
        .appendByte(0x05.toByte())
        .appendByte(0x00.toByte())
        .appendByte(0x00.toByte())
        .appendByte(0x01.toByte())
        .appendBytes(ByteArray(6) { 0x0 })
    netSocket.write(buffer)
  }

  override fun wsReceivedRawHandler(data: RawData) {
    val netSocket = connectMap[data.uuid] ?: return
    netSocket.write(data.data)
  }

  override fun wsReceivedUDPHandler(data: RawUDPData) {
    val sender = senderMap[data.uuid] ?: return
    udpServer.send(data.data, sender.port(), sender.host()) {}
  }

  override fun wsExceptionHandler(e: Exception) {
    connectMap.remove(e.uuid)?.close()
    logger.warn(e.message)
  }
}

