package me.wooy.proxy.ui

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.File
object Utils{
  fun readInfo(saveFile:File):JsonArray{
    return try {
      val info = JsonArray(saveFile.readText())
      info
    } catch (e: Throwable) {
      JsonArray()
    }
  }

  fun saveInfo(saveFile: File,info:JsonArray){
    saveFile.writeText(info.toString())
  }

  fun saveGlobalPac(saveFile: File){
    val pac = """
        function FindProxyForURL(o,c){
          return "SOCKS5 127.0.0.1:1080; SOCKS 127.0.0.1:1080; DIRECT";
        }
    """.trimIndent()
      saveFile.writeText(pac)
  }
}
