package me.wooy.proxy

import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import me.wooy.proxy.server.Master
import me.wooy.proxy.server.ServerWebSocket
import org.apache.commons.cli.*
import java.io.File

//服务端入口
fun main(args: Array<String>) {
  val options = options()
  val parser = DefaultParser()
  val formatter = HelpFormatter()

  val cmd = try {
    parser.parse(options, args)
  } catch (e: ParseException) {
    formatter.printHelp("帮助", options)
    return
  }

  val vertx = Vertx.vertx()
  val serverType = cmd.getOptionValue("type")
  val configPath = cmd.getOptionValue("config-path")
  val serverConfig = JsonObject(File(configPath).readText())
  vertx.deployVerticle(Master(),DeploymentOptions().setConfig(JsonObject()
      .put("path",configPath)
      .put("config",serverConfig)))
  when(serverType){
    "websocket"->  vertx.deployVerticle(ServerWebSocket(), DeploymentOptions().setConfig(serverConfig))
  }
}

fun options(): Options {
  val options = Options()

  val serverType = Option("T","type",true,"[websocket]")
  serverType.isRequired = true
  options.addOption(serverType)

  val configFile = Option("C", "config-path", true, "配置文件路径")
  configFile.isRequired = true
  options.addOption(configFile)

  return options
}
