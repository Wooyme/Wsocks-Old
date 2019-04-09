package me.wooy.proxy.data

import io.vertx.core.buffer.Buffer
import me.wooy.proxy.common.UserInfo
import me.wooy.proxy.encryption.Aes

class ConnectSuccess(userInfo: UserInfo,private val buffer: Buffer) {
  val uuid = String(Aes.decrypt(buffer.getBytes(Int.SIZE_BYTES,buffer.length()),userInfo.key,userInfo.doZip))
  companion object {
    fun create(userInfo: UserInfo,uuid:String):Buffer {
      val encryptedUUID = Aes.encrypt(uuid.toByteArray(),userInfo.key,userInfo.doZip)
      return Buffer.buffer()
        .appendIntLE(Flag.CONNECT_SUCCESS.ordinal)
        .appendBytes(encryptedUUID)
    }
  }
}
