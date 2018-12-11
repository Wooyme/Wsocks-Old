package me.wooy.proxy

import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import me.wooy.proxy.client.ClientSockJs
import me.wooy.proxy.server.ServerSockJs

fun main(args:Array<String>) {
  val vertx = Vertx.vertx()
  GlobalScope.launch(vertx.dispatcher()) {
    when {
        args[0] == "client" -> {
          val ip = args[1]
          val clientConfig = JsonObject().put("ip", ip)
          awaitResult<String> {
            vertx.deployVerticle(ClientSockJs(), DeploymentOptions().setConfig(clientConfig), it)
          }
        }
        args[0]=="server" -> awaitResult<String> {
          vertx.deployVerticle(ServerSockJs(), it)
        }
        args[0]=="both" -> {
          awaitResult<String> {
            vertx.deployVerticle(ServerSockJs(), it)
          }
          val clientConfig = JsonObject().put("ip", "127.0.0.1")
          awaitResult<String> {
            vertx.deployVerticle(ClientSockJs(), DeploymentOptions().setConfig(clientConfig), it)
          }
        }
    }
  }
}
