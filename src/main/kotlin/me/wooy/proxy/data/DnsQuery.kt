package me.wooy.proxy.data

import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import me.wooy.proxy.encryption.Aes

class DnsQuery(private val buffer: Buffer) {
  private val json = Buffer.buffer(Aes.decrypt(buffer.getBytes(Int.SIZE_BYTES,buffer.length()))).toJsonObject()
  val host = json.getString("host")
  val uuid get() = json.getString("uuid")
  fun toBuffer() = buffer
  companion object {
    fun create(uuid:String,host: String): DnsQuery {
      val buffer = Buffer.buffer()
          .appendBuffer(JsonObject().put("host", host).put("uuid", uuid).toBuffer())
      val encryptedBuffer = Aes.encrypt(buffer.bytes)
      return DnsQuery(Buffer.buffer()
          .appendIntLE(Flag.DNS.ordinal)
          .appendBytes(encryptedBuffer))
    }
  }
}