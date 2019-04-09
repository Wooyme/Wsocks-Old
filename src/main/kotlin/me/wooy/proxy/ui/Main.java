package me.wooy.proxy.ui;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonArray;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import me.wooy.proxy.client.ClientWebSocket;

import java.io.File;
import java.nio.file.Paths;

public class Main extends Application {
    public static Vertx vertx;
    public static File saveFile;
    public static JsonArray info = new JsonArray();
    public static void main(String[] args) {
        VertxOptions options = new VertxOptions();
        options.getFileSystemOptions().setFileCachingEnabled(false).setClassPathResolvingEnabled(false);
        vertx = Vertx.vertx(options);
        String home = System.getProperty("user.home");
        Paths.get(home, ".wsocks", "").toFile().mkdirs();
        saveFile = Paths.get(home, ".wsocks", "save.json").toFile();
        info = Utils.INSTANCE.readInfo(saveFile);
        vertx.deployVerticle(new ClientWebSocket());
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        Parent root = FXMLLoader.load(getClass().getResource("/sample.fxml"));
        primaryStage.setTitle("WSocks");
        primaryStage.setScene(new Scene(root, 660, 295));
        Platform.setImplicitExit(false);
        primaryStage.setOnCloseRequest(t -> hide(primaryStage));
        primaryStage.show();
        Tray.INSTANCE.initTray(vertx,primaryStage);
    }

    private void hide(final Stage stage) {
        Platform.runLater(stage::hide);
    }
}
