package me.wooy.proxy.ui;

import io.vertx.core.json.JsonObject;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import javax.annotation.Resources;
import java.net.URL;

public class Controller {
    @FXML
    private TextField localPortTextField;
    @FXML
    private TextField remoteAddressTextField;
    @FXML
    private TextField remotePortTextField;
    @FXML
    private TextField usernameTextField;
    @FXML
    private TextField passwordTextField;
    @FXML
    private TextField keyTextField;

    @FXML
    protected void onButtonClicked(ActionEvent event) {
        Integer localPort = new Integer(localPortTextField.getText());
        String host = remoteAddressTextField.getText();
        Integer remotePort = new Integer(remotePortTextField.getText());
        String username = usernameTextField.getText();
        String password = passwordTextField.getText();
        String key = keyTextField.getText();
        Main.info.put("proxy.type", "socks5")
                .put("local.port", localPort)
                .put("gfw.use", false)
                .put("gfw.path", "");
        Main.info.put("remote.ip", host)
                .put("remote.port", remotePort)
                .put("user", username)
                .put("pass", password)
                .put("key", key)
                .put("offset", 0);
        Main.vertx.eventBus().publish("config-modify", Main.info);
        Utils.INSTANCE.saveInfo(Main.saveFile,Main.info);
        Tray.INSTANCE.getSystemTray().setStatus("Connecting...");
        ((Stage)localPortTextField.getScene().getWindow()).close();
    }

    @FXML
    protected void initialize() {
        if (Main.info.size()>1) {
            localPortTextField.setText(String.valueOf(Main.info.getInteger("local.port")));
            remoteAddressTextField.setText(Main.info.getString("remote.ip"));
            remotePortTextField.setText(String.valueOf(Main.info.getInteger("remote.port")));
            usernameTextField.setText(Main.info.getString("user"));
            passwordTextField.setText(Main.info.getString("pass"));
            keyTextField.setText(Main.info.getString("key"));
        }
    }
}
