package me.wooy.proxy.ui

import io.vertx.core.json.JsonObject
import java.io.File
object Utils{
  fun readInfo(saveFile:File):JsonObject?{
    return try {
      val info = JsonObject(saveFile.readText())
      if (info.getString("version") != Main.VERSION)
        throw Exception("old version")
      info
    } catch (e: Throwable) {
      JsonObject().put("version",Main.VERSION)
    }
  }

  fun saveInfo(saveFile: File,info:JsonObject){
    saveFile.writeText(info.toString())
  }
}
