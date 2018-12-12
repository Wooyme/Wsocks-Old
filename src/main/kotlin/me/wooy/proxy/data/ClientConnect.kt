package me.wooy.proxy.data

import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import me.wooy.proxy.encryption.Aes

class ClientConnect(private val buffer: Buffer) {
  private val json = buffer.getBuffer(Int.SIZE_BYTES,buffer.length()).toJsonObject()
  val host get() = String(Aes.decrypt(json.getBinary("host")))
  val port get() = json.getInteger("port")
  val uuid get() = json.getString("uuid")

  fun toBuffer() = buffer
  companion object {
    fun create(uuid:String,host: String, port: Int): ClientConnect {
      val encryptedHost = Aes.encrypt(host.toByteArray())
      return ClientConnect(Buffer.buffer()
        .appendIntLE(Flag.CONNECT.ordinal)
        .appendBuffer(JsonObject().put("host", encryptedHost).put("port", port).put("uuid", uuid).toBuffer()))
    }
  }
}
