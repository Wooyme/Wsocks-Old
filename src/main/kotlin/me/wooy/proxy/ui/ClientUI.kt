package me.wooy.proxy.ui

import dorkbox.systemTray.MenuItem
import dorkbox.systemTray.SystemTray
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import java.awt.event.ActionListener
import java.io.File
import javax.swing.JOptionPane

class ClientUI : AbstractVerticle() {
  private val systemTray = SystemTray.get() ?: throw RuntimeException("Unable to load SystemTray!")
  private lateinit var info: JsonObject
  override fun start() {
    super.start()
    vertx.eventBus().consumer<JsonObject>("status-modify") {
      systemTray.status = it.body().getString("status")
    }
    initTray()
    try {
      val save = JsonObject(File("save.json").readText())
      info = save
      vertx.eventBus().publish("local-init", save)
    } catch (e: Throwable) {
      //没有保存文件就弹个窗，初始化一下几个参数
      initUI()
    }
  }

  private fun initUI() {
    vertx.executeBlocking<Int>({
      it.complete(JOptionPane.showInputDialog("Local Port").toInt())
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
          File("save.json").writeText(info.toString())
        }
      }

    }
  }

  private fun initTray() {
    systemTray.setTooltip("LightSocks")
    systemTray.setImage(LT_GRAY_TRAIN)
    systemTray.status = "Connecting"

    val mainMenu = systemTray.menu
    val editEntry = MenuItem("Edit connection"){ e ->
      remoteModify()
    }
    val quitEntry = MenuItem("Quit"){
      System.exit(0)
    }
    mainMenu.add(editEntry)
    mainMenu.add(quitEntry)
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
        val address = if(systemTray.status=="Connecting") "local-init" else "remote-modify"
        vertx.eventBus().publish(address, info
          .put("remote.ip", host)
          .put("remote.port", port)
          .put("user", user)
          .put("pass", pass))
        File("save.json").writeText(info.toString())
      }
    }
  }


  companion object {
    private val LT_GRAY_TRAIN = ClientUI::class.java.getResource("favicon-32x32.png")

  }
}
