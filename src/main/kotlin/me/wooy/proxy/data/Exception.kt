package me.wooy.proxy.data

import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import me.wooy.proxy.common.UserInfo
import me.wooy.proxy.encryption.Aes

class Exception(userInfo: UserInfo,private val buffer: Buffer) {
  private val json = Buffer.buffer(Aes.decrypt(buffer.getBytes(Int.SIZE_BYTES,buffer.length()),userInfo.key,userInfo.doZip)).toJsonObject()
  val message get() = json.getString("message")
  val uuid get() = json.getString("uuid")
  fun toBuffer() = buffer
  companion object {
    fun create(userInfo: UserInfo,uuid:String,message:String):Buffer {
      val encryptedBuffer = Aes.encrypt(JsonObject()
          .put("message", message)
          .put("uuid", uuid)
          .toBuffer().bytes,userInfo.key,userInfo.doZip)
      return Buffer.buffer().appendIntLE(Flag.EXCEPTION.ordinal).appendBytes(encryptedBuffer)
    }
  }
}
