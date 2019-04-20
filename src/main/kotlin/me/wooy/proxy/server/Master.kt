package me.wooy.proxy.server

import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import java.io.File

class Master : AbstractVerticle() {
  private val logger = LoggerFactory.getLogger(Master::class.java)
  private val path by lazy {
    config().getString("path")
  }
  private val config by lazy {
    config().getJsonObject("config")
  }
  private val port by lazy {
    config.getInteger("master")?:8880
  }
  override fun start() {
    super.start()
    vertx.createHttpServer().requestHandler {
      when (it.getParam("action")) {
        "modify_user" -> {
          addUser(it.getParam("user") ?: return@requestHandler it.response().end()
              , it.getParam("pass") ?: return@requestHandler it.response().end()
              , it.getParam("zip")?.toBoolean() ?: false
              , it.getParam("offset")?.toInt() ?: 0
              , it.getParam("multiple")?.toInt() ?: -1
              , it.getParam("limit")?.toLong() ?: -1L)
          it.response().end("{\"status\":1}")
        }
        "del_user" -> {
          removeUser(it.getParam("user"))
          it.response().end("{\"status\":1}")
        }
      }
    }.listen(port) {
      logger.info("Listen at $port")
    }
  }

  private fun addUser(username: String, password: String, doZip: Boolean, offset: Int, multipleMode: Int, limitation: Long) {
    val new = JsonObject()
        .put("user", username)
        .put("pass", password)
        .put("zip", doZip)
        .put("offset", offset)
        .put("multiple", multipleMode)
        .put("limit", limitation)
    vertx.eventBus().send("user-modify", new)
    config.getJsonArray("users").firstOrNull { (it as JsonObject).getString("user") == username }?.let {
      (it as JsonObject).mergeIn(new)
    } ?: run {
      config.getJsonArray("users").add(new)
    }
    File(path).writeText(config.toString())
  }

  private fun removeUser(username: String) {
    vertx.eventBus().send("user-remove", JsonObject().put("user", username))
    config.getJsonArray("users").forEachIndexed { index, json ->
      json as JsonObject
      if (json.getString("user") == username) {
        config.getJsonArray("users").remove(index)
        File(path).writeText(config.toString())
        return
      }
    }
  }
}
