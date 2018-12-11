package me.wooy.proxy.data

import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject

class Exception(private val buffer: Buffer) {
  private val json = buffer.getBuffer(Int.SIZE_BYTES,buffer.length()).toJsonObject()
  val message get() = json.getString("message")
  val uuid get() = json.getString("uuid")
  fun toBuffer() = buffer
  companion object {
    fun create(uuid:String,message:String) = Exception(Buffer.buffer()
      .appendIntLE(Flag.EXCEPTION.ordinal)
      .appendBuffer(JsonObject()
      .put("message",message)
      .put("uuid",uuid)
      .toBuffer()))
  }
}
