package me.wooy.proxy.data

import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject

class ClientConnect(private val buffer: Buffer) {
  private val json = buffer.getBuffer(Int.SIZE_BYTES,buffer.length()).toJsonObject()
  val host get() = json.getString("host")
  val port get() = json.getInteger("port")
  val uuid get() = json.getString("uuid")

  fun toBuffer() = buffer
  companion object {
    fun create(uuid:String,host: String, port: Int): ClientConnect = ClientConnect(Buffer.buffer()
      .appendIntLE(Flag.CONNECT.ordinal)
      .appendBuffer(JsonObject().put("host",host).put("port",port).put("uuid",uuid).toBuffer()))
  }
}
