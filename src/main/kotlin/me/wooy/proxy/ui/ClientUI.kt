package me.wooy.proxy.ui

import dorkbox.systemTray.MenuItem
import dorkbox.systemTray.SystemTray
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import java.io.File
import java.nio.file.Paths

import javax.swing.JOptionPane

class ClientUI : AbstractVerticle() {
  private val systemTray = SystemTray.get() ?: throw RuntimeException("Unable to load SystemTray!")
  private lateinit var info: JsonObject
  private lateinit var saveFile: File
  override fun start() {
    super.start()
    val home = System.getProperty("user.home")
    Paths.get(home,".wsocks","").toFile().mkdirs()
    saveFile = Paths.get(home,".wsocks","save.json").toFile()
    vertx.eventBus().consumer<JsonObject>("status-modify") {
      systemTray.status = it.body().getString("status")
    }
    initTray()
    try {
      val save = JsonObject(saveFile.readText())
      info = save
      vertx.eventBus().publish("local-init", save)
    } catch (e: Throwable) {
      //没有保存文件就弹个窗，初始化一下几个参数
      initUI()
    }
  }

  private fun initUI() {
    vertx.executeBlocking<Int>({
      var port = JOptionPane.showInputDialog("Local Port").toIntOrNull()
      while(port==null)
        port = JOptionPane.showInputDialog("Local Port").toIntOrNull()
      it.complete(port)
    }) {
      val localPort = it.result()
      vertx.executeBlocking<String>({
        it.complete(JOptionPane.showInputDialog("Input remote server(user:pass@host:port)"))
      }) {
        val remote = it.result()
        if (remote != null && remote.isNotBlank()) {
          val auth = remote.split("@")[0]
          val user = auth.split(":")[0]
          val pass = auth.split(":")[1]
          val server = remote.split("@")[1]
          val host = server.split(":")[0]
          val port = server.split(":")[1].toInt()
          info = JsonObject()
            .put("remote.ip", host)
            .put("remote.port", port)
            .put("user", user)
            .put("pass", pass)
            .put("local.port", localPort)
          vertx.eventBus().publish("local-init", info)
          saveFile.writeText(info.toString())
        }
      }

    }
  }

  private fun initTray() {
    systemTray.setTooltip("LightSocks")
    systemTray.setImage(LT_GRAY_TRAIN)
    systemTray.status = "Connecting"

    val mainMenu = systemTray.menu
    val editLocalEntry = MenuItem("Edit Local Port"){
      remoteLocal()
    }
    val editRemoteEntry = MenuItem("Edit connection"){
      remoteModify()
    }
    val reConnectEntry = MenuItem("Re-Connect"){
      if(systemTray.status!="Connecting")
        reConnectCommand()
    }
    val aboutEntry = MenuItem("About"){
      JOptionPane.showMessageDialog(null,"Wsocks https://github.com/Wooyme/Wsocks\n made by Wooyme")
    }
    val quitEntry = MenuItem("Quit"){
      System.exit(0)
    }
    mainMenu.add(editLocalEntry)
    mainMenu.add(editRemoteEntry)
    mainMenu.add(reConnectEntry)
    mainMenu.add(aboutEntry)
    mainMenu.add(quitEntry)
  }

  private fun reConnectCommand(){
    systemTray.status="Connecting"
    vertx.eventBus().publish("remote-re-connect","")
  }

  private fun remoteLocal(){
    vertx.executeBlocking<Int>({
      var port = JOptionPane.showInputDialog("Local Port").toIntOrNull()
      while(port==null){
        port = JOptionPane.showInputDialog("Local Port").toIntOrNull()
      }
      it.complete(port)
    }){
      val port = it.result()
      vertx.eventBus().publish("local-modify",JsonObject().put("port",port))
    }
  }

  private fun remoteModify() {
    vertx.executeBlocking<String>({
      val remote = JOptionPane.showInputDialog("Input remote server(user:pass@host:port)")
      it.complete(remote)
    }) { result ->
      val remote = result.result()
      if (remote != null && remote.isNotBlank()) {
        val auth = remote.split("@")[0]
        val user = auth.split(":")[0]
        val pass = auth.split(":")[1]
        val server = remote.split("@")[1]
        val host = server.split(":")[0]
        val port = server.split(":")[1].toInt()
        val address = "remote-modify"
        vertx.eventBus().publish(address, info
          .put("remote.ip", host)
          .put("remote.port", port)
          .put("user", user)
          .put("pass", pass))
        saveFile.writeText(info.toString())
      }
    }
  }


  companion object {
    private val LT_GRAY_TRAIN = ClientUI::class.java.getResource("icon.jpg")

  }
}
