package me.wooy.proxy.data

import io.vertx.core.buffer.Buffer
import me.wooy.proxy.encryption.Aes

class RawUDPData(private val buffer: Buffer) {
  private val uuidLength get() = buffer.getIntLE(Int.SIZE_BYTES)
  val uuid = buffer.getString(Int.SIZE_BYTES*2,Int.SIZE_BYTES*2+uuidLength)
  private val hostLength get() = buffer.getIntLE(Int.SIZE_BYTES*2+uuidLength)
  val host get() = String(Aes.decrypt(buffer.getBytes(Int.SIZE_BYTES*3+uuidLength,Int.SIZE_BYTES*3+uuidLength+hostLength)))
  val port get() = buffer.getIntLE(Int.SIZE_BYTES*3+uuidLength+hostLength)
  val data = Buffer.buffer(Aes.decrypt(buffer.getBytes(Int.SIZE_BYTES*3+uuidLength+hostLength,buffer.length())))
  fun toBuffer() = buffer
  companion object {
    fun create(uuid:String,host:String,port:Int,data: Buffer):RawUDPData {
      val encryptedHost = Aes.encrypt(host.toByteArray())
      return RawUDPData(Buffer.buffer()
        .appendIntLE(Flag.UDP.ordinal)
        .appendIntLE(uuid.length)
        .appendString(uuid)
        .appendIntLE(encryptedHost.size)
        .appendBytes(encryptedHost)
        .appendIntLE(port)
        .appendBytes(Aes.encrypt(data.bytes)))
    }
  }
}
