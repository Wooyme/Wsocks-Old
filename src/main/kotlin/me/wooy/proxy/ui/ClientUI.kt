package me.wooy.proxy.ui

import dorkbox.systemTray.MenuItem
import dorkbox.systemTray.SystemTray
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.awaitBlocking
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import me.wooy.proxy.client.ClientHttp
import me.wooy.proxy.client.ClientSocks5
import java.io.File
import java.nio.file.Paths
import javax.swing.*


class ClientUI : AbstractVerticle() {
  private val systemTray = SystemTray.get() ?: throw RuntimeException("Unable to load SystemTray!")
  private lateinit var info: JsonObject
  private lateinit var localPath: String
  private lateinit var saveFile: File
  private lateinit var deployID: String
  override fun start() {
    super.start()
    initTray()
    val home = System.getProperty("user.home")
    Paths.get(home, ".wsocks", "").toFile().mkdirs()
    localPath = Paths.get(home, ".wsocks", "").toAbsolutePath().toString()
    saveFile = Paths.get(home, ".wsocks", "save.json").toFile()
    try {
      val save = JsonObject(saveFile.readText())
      if (save.getString("version") != VERSION)
        throw Exception("old version")
      info = save
      val future = Future.future<String>().setHandler {
        deployID = it.result()
        vertx.eventBus().publish("config-modify", save)
      }
      when (info.getString("proxy.type")) {
        "socks5" -> vertx.deployVerticle(ClientSocks5(), future.completer())
        "http" -> vertx.deployVerticle(ClientHttp(), future.completer())
      }
      systemTray.status = "Connecting..."
    } catch (e: Throwable) {
      //没有保存文件就弹个窗，初始化一下几个参数
      initUI()
    }
  }

  private fun initUI() {
    systemTray.status = "Init..."
    info = JsonObject().put("version", VERSION)
    GlobalScope.launch(vertx.dispatcher()) { localModify() }.invokeOnCompletion {
      remoteModify()
    }

  }

  private fun initTray() {
    vertx.eventBus().consumer<JsonObject>("status-modify") {
      systemTray.status = it.body().getString("status")
    }
    systemTray.setTooltip("WSocks")
    systemTray.setImage(LT_GRAY_TRAIN)
    systemTray.status = "No Action"

    val mainMenu = systemTray.menu
    val netStatusEntry = MenuItem("0kb/s")
    vertx.eventBus().consumer<String>("net-status-update") {
      netStatusEntry.text = it.body()
    }
    val editLocalEntry = MenuItem("Edit Local") {
      GlobalScope.launch(vertx.dispatcher()) { localModify() }
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
    vertx.eventBus().publish("config-modify", info)
  }

  private suspend fun localModify() {
    val socks5Radio = JRadioButton("Socks5")
    val httpRadio = JRadioButton("HTTP")
    socks5Radio.addActionListener {
      httpRadio.isSelected = false
    }
    httpRadio.addActionListener {
      socks5Radio.isSelected = false
    }
    when(info.getString("proxy.type")){
      "socks5"->{
        socks5Radio.isSelected = true
      }
      "http"->{
        httpRadio.isSelected = true
      }
    }
    val portField = JTextField(info.getInteger("local.port")?.toString())
    val gfwListPathField = JTextField(info.getString("gfw.path") ?: Paths.get(localPath, "gfw.lst").toString())
    val gfwCheckBox = JCheckBox(null, null, info.getBoolean("gfw.use") ?: false)
    val message = arrayOf(
        httpRadio, socks5Radio,
        "Local Port", portField,
        "GFW list", gfwListPathField,
        "Use GFW list", gfwCheckBox)
    val option = awaitBlocking { JOptionPane.showConfirmDialog(null, message, "Local", JOptionPane.OK_CANCEL_OPTION) }
    if (option == JOptionPane.OK_OPTION) {
      if (this@ClientUI::deployID.isInitialized)
        vertx.undeploy(deployID)
      if (socks5Radio.isSelected) {
        info.put("proxy.type","socks5")
        deployID = awaitResult { vertx.deployVerticle(ClientSocks5(), it) }
      }else if (httpRadio.isSelected){
        info.put("proxy.type","http")
        deployID = awaitResult { vertx.deployVerticle(ClientHttp(),it) }
      }

      val port = portField.text.toInt()
      val gfwListPath = gfwListPathField.text
      val useGFWList = gfwCheckBox.isSelected
      info.put("local.port", port).put("gfw.use", useGFWList).put("gfw.path", gfwListPath)
      vertx.eventBus().publish("config-modify", info)
      saveFile.writeText(info.toString())
    }
  }

  private fun remoteModify() {
    val hostField = JTextField(info.getString("remote.ip"))
    val portField = JTextField(info.getInteger("remote.port")?.toString())
    val usernameField = JTextField(info.getString("user"))
    val passwordField = JPasswordField(info.getString("pass"))
    val keyField = JTextField(info.getString("key"))
    val message = arrayOf<Any>("Host:", hostField
        , "Port:", portField
        , "Username:", usernameField
        , "Password:", passwordField
        , "Key(Optional):", keyField)
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
        val offset = 0
        vertx.eventBus().publish("config-modify", info
            .put("remote.ip", host)
            .put("remote.port", port)
            .put("user", user)
            .put("pass", pass)
            .put("key", key)
            .put("offset", offset))
        saveFile.writeText(info.toString())
      }
    }
  }

  companion object {
    private val LT_GRAY_TRAIN = ClientUI::class.java.getResource("/icon/icon.jpg")
    private const val VERSION = "0.0.6"
  }
}
