package me.wooy.proxy.data

import io.vertx.core.buffer.Buffer
import me.wooy.proxy.encryption.Aes

class RawData(private val buffer:Buffer) {
  private val uuidLength get() = buffer.getIntLE(Int.SIZE_BYTES)
  val uuid = buffer.getString(Int.SIZE_BYTES*2,Int.SIZE_BYTES*2+uuidLength)
  val data = Buffer.buffer(Aes.decrypt(buffer.getBytes(Int.SIZE_BYTES*2+uuidLength,buffer.length())))
  fun toBuffer() = buffer
  companion object {
    fun create(uuid:String,data:Buffer) = RawData(Buffer.buffer()
      .appendIntLE(Flag.RAW.ordinal)
      .appendIntLE(uuid.length)
      .appendString(uuid)
      .appendBytes(Aes.encrypt(data.bytes)))
  }
}
