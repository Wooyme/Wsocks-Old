package me.wooy.proxy.data

import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import me.wooy.proxy.common.UserInfo
import me.wooy.proxy.encryption.Aes

class DnsQuery(userInfo: UserInfo,private val buffer: Buffer) {
  private val json = Buffer.buffer(Aes.decrypt(buffer.getBytes(Int.SIZE_BYTES,buffer.length()),userInfo.key,userInfo.doZip)).toJsonObject()
  val host = json.getString("host")
  val uuid get() = json.getString("uuid")
  companion object {
    fun create(userInfo: UserInfo,uuid:String,host: String): Buffer {
      val buffer = Buffer.buffer()
          .appendBuffer(JsonObject().put("host", host).put("uuid", uuid).toBuffer())
      val encryptedBuffer = Aes.encrypt(buffer.bytes,userInfo.key,userInfo.doZip)
      return Buffer.buffer()
          .appendIntLE(Flag.DNS.ordinal)
          .appendBytes(encryptedBuffer)
    }
  }
}