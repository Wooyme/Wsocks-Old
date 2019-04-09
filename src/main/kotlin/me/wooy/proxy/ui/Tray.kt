package me.wooy.proxy.ui

import dorkbox.systemTray.MenuItem
import dorkbox.systemTray.SystemTray
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import javafx.application.Platform
import javafx.stage.Stage

object Tray {
  val systemTray = SystemTray.get() ?: throw RuntimeException("Unable to load SystemTray!")

  private val LT_GRAY_TRAIN = Tray::class.java.getResource("/icon/icon.jpg")

  fun initTray(vertx:Vertx,stage:Stage) {
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
    val editEntry = MenuItem("Edit") {
      Platform.runLater {
        stage.show()
      }
    }
    val reConnectEntry = MenuItem("Reconnect") {
      if (systemTray.status != "Connecting..."){
        systemTray.status = "Connecting..."
        vertx.eventBus().publish("config-modify", JsonObject())
      }
    }
    val quitEntry = MenuItem("Quit") {
      System.exit(0)
    }
    mainMenu.add(netStatusEntry)
    mainMenu.add(editEntry)
    mainMenu.add(reConnectEntry)
    mainMenu.add(quitEntry)
  }
}