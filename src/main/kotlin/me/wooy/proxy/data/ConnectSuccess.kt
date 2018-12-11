package me.wooy.proxy.data

import io.vertx.core.buffer.Buffer

inline class ConnectSuccess(private val buffer: Buffer) {
  private val uuidLength get() = buffer.getIntLE(Int.SIZE_BYTES)
  val uuid get() = buffer.getString(Int.SIZE_BYTES*2,Int.SIZE_BYTES*2+uuidLength)
  fun toBuffer() = buffer
  companion object {
    fun create(uuid:String) = ConnectSuccess(Buffer.buffer()
      .appendIntLE(Flag.CONNECT_SUCCESS.ordinal).appendIntLE(uuid.length).appendString(uuid))
  }
}
