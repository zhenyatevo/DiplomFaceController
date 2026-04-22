package com.example.diplomfacecontroller;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public class MainApplication extends Application {
    private static final Logger logger = LoggerFactory.getLogger(MainApplication.class);

    static {
        try {
            // ✅ НОВОЕ: Правильная загрузка OpenCV через JavaCV
            org.bytedeco.javacpp.Loader.load(org.bytedeco.opencv.opencv_java.class);
            logger.info("OpenCV loaded successfully via JavaCV");
        } catch (Exception e) {
            logger.error("Failed to load OpenCV", e);
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public void start(Stage stage) {
        try {
            logger.info("Loading FXML from /fxml/MainWindow.fxml");

            FXMLLoader fxmlLoader = new FXMLLoader(
                    Objects.requireNonNull(
                            MainApplication.class.getResource("/fxml/MainWindow.fxml"),
                            "FXML file not found!"
                    )
            );

            Scene scene = new Scene(fxmlLoader.load(), 1000, 600);

            // Загрузка стилей
            String cssPath = "/css/style.css";
            if (MainApplication.class.getResource(cssPath) != null) {
                scene.getStylesheets().add(
                        Objects.requireNonNull(
                                getClass().getResource(cssPath)
                        ).toExternalForm()
                );
                logger.info("CSS styles loaded");
            } else {
                logger.warn("CSS file not found: {}", cssPath);
            }

            // Установка иконки приложения (опционально)
            try {
                stage.getIcons().add(new Image(
                        Objects.requireNonNull(
                                getClass().getResourceAsStream("/images/icon.png")
                        )
                ));
            } catch (Exception e) {
                logger.debug("No application icon found");
            }

            stage.setTitle("FaceController - Управление компьютером взглядом");
            stage.setScene(scene);

            // Устанавливаем минимальные размеры окна
            stage.setMinWidth(1000);
            stage.setMinHeight(700);

            // Центрируем окно на экране
            stage.centerOnScreen();

            stage.show();

            logger.info("Application started successfully with window size: {}x{}",
                    stage.getWidth(), stage.getHeight());

        } catch (NullPointerException e) {
            logger.error("Resource not found: {}", e.getMessage());
            logger.debug("Full stack trace:", e);
            showErrorDialog("Resource Error",
                    "Не удалось найти необходимый файл ресурсов.\n" +
                            "Проверьте наличие файла /fxml/MainWindow.fxml");
        } catch (Exception e) {
            logger.error("Failed to start application", e);
            logger.debug("Full stack trace:", e);
            showErrorDialog("Startup Error",
                    "Ошибка при запуске приложения:\n" + e.getMessage());
        }
    }

    /**
     * Показать диалог с ошибкой (запасной вариант если UI не загрузился)
     */
    private void showErrorDialog(String title, String message) {
        javafx.application.Platform.runLater(() -> {
            Stage dialogStage = new Stage();
            dialogStage.setTitle(title);

            javafx.scene.control.Label label = new javafx.scene.control.Label(message);
            label.setWrapText(true);

            javafx.scene.control.Button button = new javafx.scene.control.Button("OK");
            button.setOnAction(e -> dialogStage.close());

            javafx.scene.layout.VBox vbox = new javafx.scene.layout.VBox(20, label, button);
            vbox.setAlignment(javafx.geometry.Pos.CENTER);
            vbox.setPadding(new javafx.geometry.Insets(20));

            Scene scene = new Scene(vbox, 400, 200);
            dialogStage.setScene(scene);
            dialogStage.show();
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}