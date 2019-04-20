package me.wooy.proxy.ui;

import io.vertx.core.json.JsonObject;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

public class Controller {
    @FXML
    private ListView<String> listView;
    @FXML
    private TextField remoteAddressTextField;
    @FXML
    private TextField remotePortTextField;
    @FXML
    private TextField usernameTextField;
    @FXML
    private TextField passwordTextField;
    @FXML
    private CheckBox doZipCheckBox;
    private Number selected = -1;

    @FXML
    protected void onRemoveButtonClicked(ActionEvent event) {
        if (this.selected.intValue() < 0) return;
        listView.getItems().remove(selected.intValue());
        Main.info.remove(selected.intValue()+1);
        Utils.INSTANCE.saveInfo(Main.saveFile,Main.info);
        this.selected = listView.getItems().size()-1;
        if(this.selected.intValue()<0){
            remoteAddressTextField.clear();
            remotePortTextField.clear();
            usernameTextField.clear();
            passwordTextField.clear();
            doZipCheckBox.setSelected(false);
        }else{
            listView.getSelectionModel().select(this.selected.intValue());
            JsonObject config = Main.info.getJsonObject(this.selected.intValue());
            remoteAddressTextField.setText(config.getString("remote.ip"));
            remotePortTextField.setText(String.valueOf(config.getInteger("remote.port")));
            usernameTextField.setText(config.getString("user"));
            passwordTextField.setText(config.getString("pass"));
            doZipCheckBox.setSelected(config.getBoolean("zip"));
        }
    }

    @FXML
    protected void onAddButtonClicked(ActionEvent event) {
        String host = remoteAddressTextField.getText();
        Integer remotePort = new Integer(remotePortTextField.getText());
        String username = usernameTextField.getText();
        String password = passwordTextField.getText();
        boolean doZip = doZipCheckBox.isSelected();
        JsonObject config = new JsonObject();
        config.put("proxy.type", "socks5")
                .put("local.port", 1080);
        config.put("remote.ip", host)
                .put("remote.port", remotePort)
                .put("user", username)
                .put("pass", password)
                .put("zip",doZip)
                .put("offset", 0);
        Main.info.add(config);
        Utils.INSTANCE.saveInfo(Main.saveFile, Main.info);
        listView.getItems().add(config.getString("remote.ip") + ":" + config.getInteger("remote.port"));
        this.selected = listView.getItems().size()-1;
    }

    @FXML
    protected void onConfirmButtonClicked(ActionEvent event) {
        String host = remoteAddressTextField.getText();
        Integer remotePort = new Integer(remotePortTextField.getText());
        String username = usernameTextField.getText();
        String password = passwordTextField.getText();
        boolean doZip = doZipCheckBox.isSelected();
        JsonObject config = new JsonObject();
        config.put("proxy.type", "socks5")
                .put("local.port", 1080);
        config.put("remote.ip", host)
                .put("remote.port", remotePort)
                .put("user", username)
                .put("pass", password)
                .put("zip",doZip)
                .put("offset", 0);
        if (selected.intValue() >= 0) {
            Main.info.getJsonObject(selected.intValue()).put("selected", true);
            Utils.INSTANCE.saveInfo(Main.saveFile,Main.info);
        }
        Main.vertx.eventBus().publish("config-modify", config);
        Tray.INSTANCE.getSystemTray().setStatus("Connecting...");
        ((Stage) remoteAddressTextField.getScene().getWindow()).close();
    }

    @FXML
    protected void initialize() {
        listView.getSelectionModel()
                .selectedIndexProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue.intValue() < 0) return;
            this.selected = newValue;
            JsonObject config = Main.info.getJsonObject(this.selected.intValue());
            remoteAddressTextField.setText(config.getString("remote.ip"));
            remotePortTextField.setText(String.valueOf(config.getInteger("remote.port")));
            usernameTextField.setText(config.getString("user"));
            passwordTextField.setText(config.getString("pass"));
            if(config.containsKey("zip")){
                doZipCheckBox.setSelected(config.getBoolean("zip"));
            }
        });
        Main.info.stream().forEach((value) -> {
            JsonObject config = (JsonObject) value;
            if (config.containsKey("selected") && config.getBoolean("selected")) {
                remoteAddressTextField.setText(config.getString("remote.ip"));
                remotePortTextField.setText(String.valueOf(config.getInteger("remote.port")));
                usernameTextField.setText(config.getString("user"));
                passwordTextField.setText(config.getString("pass"));
                if(config.containsKey("zip")){
                    doZipCheckBox.setSelected(config.getBoolean("zip"));
                }
            }
            listView.getItems().add(config.getString("remote.ip") + ":" + config.getInteger("remote.port"));
        });
    }
}
