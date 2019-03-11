package me.wooy.proxy.client

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.WebSocket
import io.vertx.core.net.NetSocket
import java.net.Inet4Address

class DNSClient(private val vertx:Vertx,private val ws:WebSocket) {
  private val dnsClient by lazy {
    vertx.createDnsClient(53,"114.114.114.114")
  }
  fun start() {
    println("Run here")
    vertx.createNetServer().connectHandler {socket->
      socket.handler{ buf->
        handleQuery(socket,buf)
      }
    }.listen(5553){
      println("Listen at 5553")
    }
  }

  private fun handleQuery(socket:NetSocket,buffer:Buffer){
    val id = buffer.getShort(2)
    val sb = StringBuilder()
    fun getName(buffer: Buffer,offset:Int):Int{
      val length = buffer.getByte(offset)

      if(length==0.toByte()){
        return offset + 1
      }
      val bytes = buffer.getString(offset+1,offset+1+length.toInt())
      sb.append(bytes).append(".")
      return getName(buffer,offset+1+length.toInt())
    }
    getName(buffer,14)
    val end = buffer.length()
    val host = sb.toString().removeSuffix(".")
    dnsClient.lookup4(host){
      if(it.failed())
        return@lookup4 it.cause().printStackTrace()
      println("Host:$host,IP:${it.result()}")
      val buf = response(id,it.result(),buffer.getBuffer(14,end))
      socket.write(Buffer.buffer().appendShort(buf.length().toShort()).appendBuffer(buf)).end()
    }
  }

  private fun response(id:Short,ip:String,query: Buffer):Buffer{
    val address = Inet4Address.getByName(ip).address
    val buffer = Buffer.buffer()
    buffer.appendShort(id)
    buffer.appendUnsignedShort(0x8180)
    buffer.appendUnsignedShort(1)
    buffer.appendUnsignedShort(1)
    buffer.appendUnsignedShort(0)
    buffer.appendUnsignedShort(0)
    buffer.appendBuffer(query)
    buffer.appendUnsignedShort(0xC00C)
    buffer.appendUnsignedShort(1)
    buffer.appendUnsignedShort(1)
    buffer.appendUnsignedInt(86400)
    buffer.appendUnsignedShort(4)
    buffer.appendBytes(address)
    return buffer
  }
}