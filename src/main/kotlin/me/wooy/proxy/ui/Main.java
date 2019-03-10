package me.wooy.proxy.ui;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import me.wooy.proxy.client.ClientSocks5;

import java.awt.*;
import java.io.File;
import java.nio.file.Paths;

public class Main extends Application {
    public static Vertx vertx;
    public static File saveFile;
    public static String VERSION = "0.0.6";
    public static JsonObject info = new JsonObject().put("version",VERSION);
    public static void main(String[] args) {
        vertx = Vertx.vertx();
        String home = System.getProperty("user.home");
        Paths.get(home, ".wsocks", "").toFile().mkdirs();
        saveFile = Paths.get(home, ".wsocks", "save.json").toFile();
        info = Utils.INSTANCE.readInfo(saveFile);
        vertx.deployVerticle(new ClientSocks5());
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        Parent root = FXMLLoader.load(getClass().getResource("/sample.fxml"));
        primaryStage.setTitle("Main Window");
        primaryStage.setScene(new Scene(root, 660, 200));
        Platform.setImplicitExit(false);
        primaryStage.setOnCloseRequest(t -> hide(primaryStage));
        primaryStage.show();
        Tray.INSTANCE.initTray(vertx,primaryStage);
    }

    private void hide(final Stage stage) {
        Platform.runLater(stage::hide);
    }
}
