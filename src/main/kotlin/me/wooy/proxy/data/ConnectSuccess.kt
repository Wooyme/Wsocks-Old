package me.wooy.proxy.data

import io.vertx.core.buffer.Buffer
import me.wooy.proxy.encryption.Aes

class ConnectSuccess(private val buffer: Buffer) {
  val uuid = String(Aes.decrypt(buffer.getBytes(Int.SIZE_BYTES,buffer.length())))
  fun toBuffer() = buffer
  companion object {
    fun create(uuid:String):ConnectSuccess {
      val encryptedUUID = Aes.encrypt(uuid.toByteArray())
      return ConnectSuccess(Buffer.buffer()
        .appendIntLE(Flag.CONNECT_SUCCESS.ordinal)
        .appendBytes(encryptedUUID))
    }
  }
}
