package me.wooy.proxy.data

import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import me.wooy.proxy.encryption.Aes

class Exception(private val buffer: Buffer) {
  private val json = Buffer.buffer(Aes.decrypt(buffer.getBytes(Int.SIZE_BYTES,buffer.length()))).toJsonObject()
  val message get() = json.getString("message")
  val uuid get() = json.getString("uuid")
  fun toBuffer() = buffer
  companion object {
    fun create(uuid:String,message:String):Exception {
      val encryptedBuffer = Aes.encrypt(JsonObject()
          .put("message", message)
          .put("uuid", uuid)
          .toBuffer().bytes)
      return Exception(Buffer.buffer().appendIntLE(Flag.EXCEPTION.ordinal).appendBytes(encryptedBuffer))
    }
  }
}
