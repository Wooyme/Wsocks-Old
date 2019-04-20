package me.wooy.proxy.ui

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import dorkbox.systemTray.MenuItem
import dorkbox.systemTray.SystemTray
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import javafx.application.Platform
import javafx.stage.Stage
import java.nio.file.Paths

object Tray {
    val systemTray = SystemTray.get() ?: throw RuntimeException("Unable to load SystemTray!")

    private val LT_GRAY_TRAIN = Tray::class.java.getResource("/icon/icon.jpg")

    fun initTray(vertx: Vertx, stage: Stage) {
        vertx.eventBus().consumer<JsonObject>("status-modify") {
            systemTray.status = it.body().getString("status")
        }
        systemTray.setTooltip("WSocks")
        systemTray.setImage(LT_GRAY_TRAIN)
        systemTray.status = "No Action"

        val mainMenu = systemTray.menu
        val proxySettingEntry = MenuItem("1.PAC代理")
        Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER,"Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings","AutoConfigURL")
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings","AutoConfigURL"
                , "https://blackwhite.txthinking.com/white.pac")
        Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings","ProxyEnable"
                , 0)
        proxySettingEntry.setCallback {
            if (proxySettingEntry.text.startsWith("1")) {
                proxySettingEntry.text = "2.全局代理"
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
                        ,"AutoConfigURL", "file://" + Paths.get(System.getProperty("user.home"), ".wsocks", "global.pac"))
                Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
                        , "ProxyEnable",0)
            } else {
                proxySettingEntry.text = "1.PAC代理"
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
                        ,"AutoConfigURL", "https://blackwhite.txthinking.com/black.pac")
                Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
                        ,"ProxyEnable", 0)
            }
        }
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
            if (systemTray.status != "Connecting...") {
                systemTray.status = "Connecting..."
                vertx.eventBus().publish("config-modify", JsonObject())
            }
        }
        val quitEntry = MenuItem("Quit") {
            System.exit(0)
        }
        if (System.getProperty("os.name").contains("Windows")) {
            mainMenu.add(proxySettingEntry)
        }
        mainMenu.add(netStatusEntry)
        mainMenu.add(editEntry)
        mainMenu.add(reConnectEntry)
        mainMenu.add(quitEntry)
    }
}