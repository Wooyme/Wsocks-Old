package me.wooy.proxy.data

import io.vertx.core.buffer.Buffer
import me.wooy.proxy.encryption.Aes

class RawData(private val buffer:Buffer) {
  private val decryptedBuffer = Buffer.buffer(Aes.decrypt(buffer.getBytes(Int.SIZE_BYTES,buffer.length())))
  private val uuidLength = decryptedBuffer.getIntLE(0)
  val uuid = decryptedBuffer.getString(Int.SIZE_BYTES,Int.SIZE_BYTES+uuidLength)
  val data = decryptedBuffer.getBuffer(Int.SIZE_BYTES+uuidLength,decryptedBuffer.length())
  fun toBuffer() = buffer
  companion object {
    fun create(uuid:String,data:Buffer):RawData {
      val encryptedBuffer = Aes.encrypt(Buffer.buffer()
        .appendIntLE(uuid.length)
        .appendString(uuid)
        .appendBuffer(data).bytes)

      return RawData(Buffer.buffer()
        .appendIntLE(Flag.RAW.ordinal)
        .appendBytes(encryptedBuffer))
    }
  }
}
