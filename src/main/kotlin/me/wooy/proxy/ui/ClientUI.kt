package me.wooy.proxy.ui

import dorkbox.systemTray.MenuItem
import dorkbox.systemTray.SystemTray
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JCheckBox

import javax.swing.JOptionPane
import javax.swing.JPasswordField
import javax.swing.JTextField

class ClientUI : AbstractVerticle() {
  private val systemTray = SystemTray.get() ?: throw RuntimeException("Unable to load SystemTray!")
  private lateinit var info: JsonObject
  private lateinit var localPath: String
  private lateinit var saveFile: File
  override fun start() {
    super.start()
    val home = System.getProperty("user.home")
    Paths.get(home, ".wsocks", "").toFile().mkdirs()
    localPath = Paths.get(home,".wsocks","").toAbsolutePath().toString()
    saveFile = Paths.get(home, ".wsocks", "save.json").toFile()
    initTray()
    try {
      val save = JsonObject(saveFile.readText())
      info = save
      vertx.eventBus().publish("remote-modify", save)
      vertx.eventBus().publish("local-modify", save)
    } catch (e: Throwable) {
      //没有保存文件就弹个窗，初始化一下几个参数
      initUI()
    }
  }

  private fun initUI() {
    info = JsonObject()
    localModify()
    remoteModify()
  }

  private fun initTray() {
    vertx.eventBus().consumer<JsonObject>("status-modify") {
      systemTray.status = it.body().getString("status")
    }
    systemTray.setTooltip("LightSocks")
    systemTray.setImage(LT_GRAY_TRAIN)
    systemTray.status = "Connecting"

    val mainMenu = systemTray.menu
    val netStatusEntry = MenuItem("0kb/s")
    vertx.eventBus().consumer<String>("net-status-update") {
      netStatusEntry.text = it.body()
    }
    val editLocalEntry = MenuItem("Edit Local") {
      localModify()
    }
    val editRemoteEntry = MenuItem("Edit Remote") {
      remoteModify()
    }
    val reConnectEntry = MenuItem("Re-Connect") {
      if (systemTray.status != "Connecting")
        reConnectCommand()
    }
    val aboutEntry = MenuItem("About") {
      JOptionPane.showMessageDialog(null, "Wsocks https://github.com/Wooyme/Wsocks\n made by Wooyme")
    }
    val quitEntry = MenuItem("Quit") {
      System.exit(0)
    }
    mainMenu.add(netStatusEntry)
    mainMenu.add(editLocalEntry)
    mainMenu.add(editRemoteEntry)
    mainMenu.add(reConnectEntry)
    mainMenu.add(aboutEntry)
    mainMenu.add(quitEntry)
  }

  private fun reConnectCommand() {
    systemTray.status = "Connecting"
    vertx.eventBus().publish("remote-re-connect", "")
  }

  private fun localModify() {
    val portField = JTextField(info.getInteger("local.port")?.toString())
    val gfwListPathField = JTextField(info.getString("gfw.path")?:Paths.get(localPath,"gfw.lst").toString())
    val gfwCheckBox = JCheckBox(null,null,info.getBoolean("gfw.use")?:false)
    vertx.executeBlocking<Int>({
      val message = arrayOf("Local Port",portField,"GFW list",gfwListPathField,"Use GFW list",gfwCheckBox)
      val option = JOptionPane.showConfirmDialog(null, message, "Local", JOptionPane.OK_CANCEL_OPTION)
      it.complete(option)
    }) {
      if(it.result()==JOptionPane.OK_OPTION){
        val port = portField.text.toInt()
        val gfwListPath = gfwListPathField.text
        val useGFWList = gfwCheckBox.isSelected
        info.put("local.port",port).put("gfw.use",useGFWList).put("gfw.path",gfwListPath)
        saveFile.writeText(info.toString())
        vertx.eventBus().publish("local-modify", info)
      }
    }
  }

  private fun remoteModify() {
    val hostField = JTextField(info.getString("remote.ip"))
    val portField = JTextField(info.getInteger("remote.port")?.toString())
    val usernameField = JTextField(info.getString("user"))
    val passwordField = JPasswordField(info.getString("pass"))
    val keyField = JTextField(info.getString("key"))
    val offsetField = JTextField(info.getInteger("offset")?.toString())
    val message = arrayOf<Any>("Host:", hostField
      , "Port:", portField
      , "Username:", usernameField
      , "Password:", passwordField
      , "Key(Optional):", keyField
      , "offset(Optional):", offsetField)
    vertx.executeBlocking<Int>({
      val option = JOptionPane.showConfirmDialog(null, message, "Connect", JOptionPane.OK_CANCEL_OPTION)
      it.complete(option)
    }) { result ->
      if (result.result() == JOptionPane.OK_OPTION) {
        val host = hostField.text
        val port = portField.text.toInt()
        val user = usernameField.text
        val pass = String(passwordField.password)
        val key = keyField.text
        val offset = offsetField.text.toInt()
        vertx.eventBus().publish("remote-modify", info
          .put("remote.ip", host)
          .put("remote.port", port)
          .put("user", user)
          .put("pass", pass)
          .put("key",key)
          .put("offset",offset))
        saveFile.writeText(info.toString())
      }
    }
  }

  companion object {
    private val LT_GRAY_TRAIN = ClientUI::class.java.getResource("/icon/icon.jpg")

  }
}
