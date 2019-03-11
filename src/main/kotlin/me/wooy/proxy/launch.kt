package me.wooy.proxy

import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import me.wooy.proxy.client.ClientHttp
import me.wooy.proxy.client.ClientSocks5
import me.wooy.proxy.server.ServerWebSocket
import org.apache.commons.cli.*

//无GUI版本，包括服务端与客户端
fun main(args:Array<String>) {
  val options = options()
  val parser = DefaultParser()
  val formatter = HelpFormatter()
  val cmd = try{
    parser.parse(options,args)
  }catch (e:ParseException){
    formatter.printHelp("帮助", options)
    return
  }
  if(cmd.hasOption("help")){
    formatter.printHelp("帮助", options)
    return
  }
  val vertx = Vertx.vertx()
  GlobalScope.launch(vertx.dispatcher()) {
    when(cmd.getOptionValue("type")){
      "client-http","client-socks5"->{
        val user = cmd.getOptionValue("user")
        val pass = cmd.getOptionValue("pass")
        val localPort = cmd.getOptionValue("local-port").toInt()
        val remotePort = cmd.getOptionValue("remote-port").toInt()
        val remoteIp = cmd.getOptionValue("remote-ip")
        val key = cmd.getOptionValue("key")
        val offset = cmd.getOptionValue("offset")
        val clientConfig = JsonObject()
          .put("local.port",localPort)
          .put("remote.ip", remoteIp)
          .put("remote.port",remotePort)
          .put("user",user)
          .put("pass",pass)
          .put("key",key)
          .put("offset",offset)
        awaitResult<String> {
          if(cmd.getOptionValue("type")=="client-http")
            vertx.deployVerticle(ClientHttp(), DeploymentOptions().setConfig(clientConfig), it)
          else
            vertx.deployVerticle(ClientSocks5(), DeploymentOptions().setConfig(clientConfig), it)
        }
      }
      "server"->{
        val configPath = cmd.getOptionValue("config-path")
        val serverConfig = JsonObject().put("config.path",configPath)
        awaitResult<String> {
          vertx.deployVerticle(ServerWebSocket(),DeploymentOptions().setConfig(serverConfig), it)
        }
      }
    }
  }
}


fun options():Options{
  val options = Options()
  val proxyType = Option("T","type",true,"[server/client-socks5/client-http]")
  proxyType.isRequired = true
  options.addOption(proxyType)

  val localPort = Option("LP","local-port",true,"本地端口")
  localPort.isRequired = false
  options.addOption(localPort)

  val remoteIp = Option("RI","remote-ip",true,"远程服务器地址")
  remoteIp.isRequired = false
  options.addOption(remoteIp)

  val remotePort = Option("RP","remote-port",true,"远程服务器端口")
  remotePort.isRequired = false
  options.addOption(remotePort)

  val user = Option("U","user",true,"客户端登录账户")
  user.isRequired = false
  options.addOption(user)

  val pwd = Option("P","pass",true,"客户端登录密码")
  pwd.isRequired = false
  options.addOption(pwd)

  val key = Option("K","key",true,"加密秘钥")
  key.isRequired = false
  options.addOption(key)

  val offset = Option("O","offset",true,"数据偏移")
  offset.isRequired = false
  options.addOption(offset)

  val configFile = Option("C","config-path",true,"配置文件路径")
  configFile.isRequired = false
  options.addOption(configFile)

  val help = Option("H","help",false,"帮助")
  help.isRequired = false
  options.addOption(help)
  return options
}
