package me.wooy.proxy.data

import io.vertx.core.buffer.Buffer
import me.wooy.proxy.common.UserInfo
import me.wooy.proxy.encryption.Aes

class RawData(userInfo: UserInfo,buffer:Buffer) {
  private val decryptedBuffer = Buffer.buffer(Aes.decrypt(buffer.getBytes(Int.SIZE_BYTES,buffer.length()),userInfo.key,userInfo.doZip))
  private val uuidLength = decryptedBuffer.getIntLE(0)
  val uuid = decryptedBuffer.getString(Int.SIZE_BYTES,Int.SIZE_BYTES+uuidLength)
  val data = decryptedBuffer.getBuffer(Int.SIZE_BYTES+uuidLength,decryptedBuffer.length())
  companion object {
    fun create(userInfo: UserInfo,uuid:String,data:Buffer):Buffer {
      val encryptedBuffer = Aes.encrypt(Buffer.buffer()
        .appendIntLE(uuid.length)
        .appendString(uuid)
        .appendBuffer(data).bytes,userInfo.key,userInfo.doZip)

      return Buffer.buffer()
        .appendIntLE(Flag.RAW.ordinal)
        .appendBytes(encryptedBuffer)
    }
  }
}
