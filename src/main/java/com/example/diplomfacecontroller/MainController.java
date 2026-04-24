package com.example.diplomfacecontroller;

import com.example.diplomfacecontroller.core.CalibrationManager;
import com.example.diplomfacecontroller.core.CameraManager;
import com.example.diplomfacecontroller.core.FaceProcessor;
import com.example.diplomfacecontroller.core.GazeEstimator;
import com.example.diplomfacecontroller.input.KeyboardController;
import com.example.diplomfacecontroller.input.MouseController;
import com.example.diplomfacecontroller.models.FaceData;
import com.example.diplomfacecontroller.models.GazeData;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Point2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.geometry.Pos;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class MainController implements Initializable {
    private static final Logger logger = LoggerFactory.getLogger(MainController.class);

    // Основные контейнеры
    @FXML private BorderPane rootPane;
    @FXML private StackPane camera1Container;
    @FXML private StackPane camera2Container;
    @FXML private VBox keyboardContainer;

    // Информационные метки
    @FXML private Label statusLabel;
    @FXML private Label headPoseLabel;
    @FXML private Label gazeLabel;
    @FXML private Label expressionLabel;
    @FXML private Label textOutputLabel;

    // Кнопки
    @FXML private Button calibrationButton;
    @FXML private Button startButton;
    @FXML private Button stopButton;
    @FXML private Button toggleMouseButton;
    @FXML private Button resetMouseButton;

    private final ImageView camera1View = new ImageView();
    private final ImageView camera2View = new ImageView();
    private CameraManager cameraManager;
    private FaceProcessor faceProcessor;
    private GazeEstimator gazeEstimator;
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private Thread dataProcessingThread;
    private MouseController mouseController;
    private final AtomicBoolean mouseControlEnabled = new AtomicBoolean(false);
    private KeyboardController keyboardController;
    private Canvas keyboardCanvas;
    private boolean keyboardEnabled = false;

    // Таймер для проверки состояния камер
    private javafx.animation.AnimationTimer cameraStatusChecker;

    // ID камер (final)
    private final int faceCameraId = 1;
    private final int eyeCameraId = 0;

    // ===== ГЛОБАЛЬНЫЕ ХОТКЕИ =====
    private NativeKeyListener globalHotkeyListener;
    private boolean globalHotkeysRegistered = false;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        logger.info("Initializing MainController");

        // Настраиваем размеры ImageView
        setupImageViews();

        // Добавляем в контейнеры
        camera1Container.getChildren().add(camera1View);
        camera2Container.getChildren().add(camera2View);

        // Создаем Canvas для клавиатуры
        setupKeyboardCanvas();

        // Устанавливаем начальные значения
        resetUIDisplay();

        // Инициализация менеджеров и процессоров
        initializeComponents();

        // Инициализация состояния кнопок
        initializeButtons();

        // Запускаем проверку состояния камер
        startCameraStatusChecker();

        logger.info("MainController initialized successfully");
        logger.info("Using cameras - Face ID: {}, Eye ID: {}", faceCameraId, eyeCameraId);

        // ===== ЛОКАЛЬНЫЕ хоткеи (работают только когда окно в фокусе) =====
        Platform.runLater(this::setupLocalHotkeys);

        // ===== ГЛОБАЛЬНЫЕ хоткеи (работают даже когда окно не в фокусе) =====
        setupGlobalHotkeys();

        // Регистрируем корректное освобождение JNativeHook при закрытии окна
        Platform.runLater(() -> {
            Stage stage = (Stage) rootPane.getScene().getWindow();
            if (stage != null) {
                stage.setOnCloseRequest(ev -> cleanup());
            }
        });
    }

    private void setupImageViews() {
        camera1View.setFitWidth(480);
        camera1View.setFitHeight(360);
        camera1View.setPreserveRatio(true);

        camera2View.setFitWidth(240);
        camera2View.setFitHeight(180);
        camera2View.setPreserveRatio(true);
    }

    private void setupKeyboardCanvas() {
        keyboardCanvas = new Canvas(600, 300);
        keyboardCanvas.setVisible(false);
        keyboardContainer.getChildren().add(keyboardCanvas);
        keyboardContainer.setAlignment(Pos.CENTER);
    }

    private void resetUIDisplay() {
        statusLabel.setText("Статус: Готов к работе");
        headPoseLabel.setText("Голова: 0° | 0° | 0°");
        gazeLabel.setText("Взгляд: X:0 Y:0");
        expressionLabel.setText("Мимика: Нейтрально");
        textOutputLabel.setText("");
    }

    private void initializeComponents() {
        cameraManager = new CameraManager(camera1View, camera2View);
        faceProcessor = new FaceProcessor();
        gazeEstimator = new GazeEstimator();
        gazeEstimator.startNeuralMode(); // запускает Python в фоне

        // ========== ВАЖНО: Устанавливаем GazeEstimator в CameraManager ==========
        cameraManager.setGazeEstimator(gazeEstimator);
        logger.info("GazeEstimator set in CameraManager");

        mouseController = new MouseController();
        keyboardController = new KeyboardController();
        rootPane.setPrefHeight(600);
    }

    private void initializeButtons() {
        if (calibrationButton != null) {
            calibrationButton.setDisable(true);
            Tooltip.install(calibrationButton, new Tooltip("Сначала запустите камеры"));
        }
        if (stopButton != null) {
            stopButton.setDisable(true);
        }
        if (startButton != null) {
            startButton.setDisable(false);
        }
        if (toggleMouseButton != null) {
            toggleMouseButton.setDisable(true);
            toggleMouseButton.setText("Вкл управление взглядом");
            Tooltip.install(toggleMouseButton, new Tooltip("Сначала выполните калибровку"));
        }
        if (resetMouseButton != null) {
            resetMouseButton.setDisable(true);
            Tooltip.install(resetMouseButton, new Tooltip("Сначала включите управление взглядом"));
        }
    }

    // ================================================================
    //  ГОРЯЧИЕ КЛАВИШИ
    // ================================================================

    /**
     * ЛОКАЛЬНЫЕ хоткеи через JavaFX (работают только когда окно в фокусе).
     * Это подстраховка на случай, если JNativeHook не смог зарегистрироваться.
     */
    private void setupLocalHotkeys() {
        if (rootPane.getScene() == null) {
            logger.warn("Scene not available for local hotkeys");
            return;
        }

        KeyCodeCombination emergencyStop =
                new KeyCodeCombination(KeyCode.Q, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN);
        KeyCodeCombination pauseMouse =
                new KeyCodeCombination(KeyCode.P, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN);
        KeyCodeCombination centerMouse =
                new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN);

        rootPane.getScene().getAccelerators().put(emergencyStop,  this::emergencyStop);
        rootPane.getScene().getAccelerators().put(pauseMouse,     this::toggleMouseControlHotkey);
        rootPane.getScene().getAccelerators().put(centerMouse,    this::resetMousePosition);

        logger.info("Local hotkeys registered: Ctrl+Shift+Q (stop), Ctrl+Shift+P (pause mouse), Ctrl+Shift+R (center)");
    }

    /**
     * ГЛОБАЛЬНЫЕ хоткеи через JNativeHook — работают даже когда окно не в фокусе.
     * Это критично: если gaze-курсор улетит в угол экрана, ты не сможешь
     * вернуть фокус в приложение Alt+Tab'ом, но глобальный хоткей сработает.
     */
    private void setupGlobalHotkeys() {
        try {
            // Отключаем подробное логирование JNativeHook, иначе он спамит INFO
            java.util.logging.Logger nativeLogger =
                    java.util.logging.Logger.getLogger(GlobalScreen.class.getPackage().getName());
            nativeLogger.setLevel(Level.WARNING);
            nativeLogger.setUseParentHandlers(false);

            GlobalScreen.registerNativeHook();

            globalHotkeyListener = new NativeKeyListener() {
                @Override
                public void nativeKeyPressed(NativeKeyEvent e) {
                    int mods = e.getModifiers();
                    boolean ctrl  = (mods & NativeKeyEvent.CTRL_MASK)  != 0;
                    boolean shift = (mods & NativeKeyEvent.SHIFT_MASK) != 0;

                    if (!(ctrl && shift)) return;

                    int key = e.getKeyCode();

                    if (key == NativeKeyEvent.VC_Q) {
                        logger.info("GLOBAL HOTKEY: Ctrl+Shift+Q -> emergency stop");
                        Platform.runLater(MainController.this::emergencyStop);
                    } else if (key == NativeKeyEvent.VC_P) {
                        logger.info("GLOBAL HOTKEY: Ctrl+Shift+P -> toggle mouse control");
                        Platform.runLater(MainController.this::toggleMouseControlHotkey);
                    } else if (key == NativeKeyEvent.VC_R) {
                        logger.info("GLOBAL HOTKEY: Ctrl+Shift+R -> center mouse");
                        Platform.runLater(MainController.this::resetMousePosition);
                    }
                }

                @Override public void nativeKeyReleased(NativeKeyEvent e) {}
                @Override public void nativeKeyTyped(NativeKeyEvent e) {}
            };

            GlobalScreen.addNativeKeyListener(globalHotkeyListener);
            globalHotkeysRegistered = true;

            logger.info("GLOBAL hotkeys registered successfully:");
            logger.info("  Ctrl+Shift+Q — экстренный стоп (работает везде)");
            logger.info("  Ctrl+Shift+P — пауза/возобновление управления мышью");
            logger.info("  Ctrl+Shift+R — центрирование курсора");

        } catch (NativeHookException e) {
            logger.error("Failed to register global hotkeys: {}", e.getMessage());
            logger.warn("Fallback: only LOCAL hotkeys will work (when window is focused)");
        } catch (Exception e) {
            logger.error("Unexpected error setting up global hotkeys", e);
        }
    }

    /**
     * Экстренный полный стоп: отключает управление мышью и останавливает трекинг.
     */
    private void emergencyStop() {
        logger.warn("EMERGENCY STOP triggered");
        if (mouseController != null) {
            mouseController.setEnabled(false);
        }
        mouseControlEnabled.set(false);

        // Только если трекинг запущен — останавливаем
        if (processing.get()) {
            stopTracking();
        }

        Platform.runLater(() -> {
            statusLabel.setText("Статус: ЭКСТРЕННАЯ ОСТАНОВКА");
            if (toggleMouseButton != null && gazeEstimator != null && gazeEstimator.isCalibrated()) {
                toggleMouseButton.setText("Вкл управление взглядом");
            }
        });
    }

    /**
     * Переключение управления мышью по хоткею.
     * Отличается от обычной кнопки тем, что не показывает алерт —
     * просто молча игнорирует, если калибровка не выполнена.
     */
    private void toggleMouseControlHotkey() {
        if (gazeEstimator == null || !gazeEstimator.isCalibrated()) {
            logger.info("Hotkey ignored: calibration not done");
            Platform.runLater(() ->
                    statusLabel.setText("Статус: Сначала выполните калибровку"));
            return;
        }

        boolean newState = !mouseControlEnabled.get();
        mouseControlEnabled.set(newState);
        mouseController.setEnabled(newState);

        if (newState) {
            mouseController.resetToCenter();
        }

        Platform.runLater(() -> {
            statusLabel.setText("Статус: Управление взглядом " +
                    (newState ? "включено" : "ПРИОСТАНОВЛЕНО"));
            if (toggleMouseButton != null) {
                toggleMouseButton.setText(newState ? "Выкл управление взглядом"
                        : "Вкл управление взглядом");
            }
        });

        logger.info("Mouse control toggled by hotkey: {}", newState);
    }

    /**
     * Вызывается при закрытии окна — освобождает JNativeHook.
     */
    private void cleanup() {
        logger.info("Cleanup: unregistering global hooks");
        try {
            if (globalHotkeysRegistered) {
                if (globalHotkeyListener != null) {
                    GlobalScreen.removeNativeKeyListener(globalHotkeyListener);
                }
                GlobalScreen.unregisterNativeHook();
                globalHotkeysRegistered = false;
            }
        } catch (Exception e) {
            logger.error("Error during cleanup: {}", e.getMessage());
        }

        if (processing.get()) {
            stopTracking();
        }
    }

    private void startCameraStatusChecker() {
        cameraStatusChecker = new javafx.animation.AnimationTimer() {
            private long lastCheck = 0;

            @Override
            public void handle(long now) {
                if (now - lastCheck >= 500_000_000) {
                    updateButtonStates();
                    lastCheck = now;
                }
            }
        };
        cameraStatusChecker.start();
    }

    private void updateButtonStates() {
        boolean camerasRunning = cameraManager != null && cameraManager.isRunning();
        boolean faceReady = cameraManager != null && cameraManager.isFaceCameraReady();
        boolean eyeReady = cameraManager != null && cameraManager.isEyeCameraReady();
        boolean camerasReady = camerasRunning && faceReady && eyeReady;
        boolean isCalibrated = gazeEstimator != null && gazeEstimator.isCalibrated();

        Platform.runLater(() -> {
            if (calibrationButton != null) {
                calibrationButton.setDisable(!camerasReady);
                updateCalibrationTooltip(camerasRunning, faceReady, eyeReady);
            }
            if (stopButton != null) {
                stopButton.setDisable(!camerasRunning);
            }
            if (startButton != null) {
                startButton.setDisable(camerasRunning);
            }
            if (toggleMouseButton != null) {
                toggleMouseButton.setDisable(!isCalibrated);
                if (isCalibrated) {
                    toggleMouseButton.setText(mouseControlEnabled.get() ? "Выкл управление взглядом" : "Вкл управление взглядом");
                }
            }
            if (resetMouseButton != null) {
                resetMouseButton.setDisable(!mouseControlEnabled.get());
            }
        });
    }

    private void updateCalibrationTooltip(boolean camerasRunning, boolean faceReady, boolean eyeReady) {
        if (calibrationButton == null) return;

        if (!camerasRunning) {
            Tooltip.install(calibrationButton, new Tooltip("Сначала запустите камеры"));
        } else if (!faceReady || !eyeReady) {
            Tooltip.install(calibrationButton, new Tooltip("Камеры еще не готовы, подождите..."));
        } else {
            Tooltip.install(calibrationButton, new Tooltip("Запустить калибровку взгляда"));
        }
    }

    @FXML
    private void openSettings() {
        logger.info("Opening settings");
        statusLabel.setText("Статус: Настройки");
    }

    @FXML
    private void startCalibration() {
        if (cameraManager == null || !cameraManager.isRunning() ||
                !cameraManager.isFaceCameraReady() || !cameraManager.isEyeCameraReady()) {

            logger.warn("Cannot start calibration: cameras not ready");
            showAlert("Калибровка недоступна",
                    "Камеры не готовы. Пожалуйста, запустите камеры и подождите.");
            return;
        }

        logger.info("Starting calibration");
        statusLabel.setText("Статус: Калибровка...");

        if (calibrationButton != null) {
            calibrationButton.setDisable(true);
        }

        Stage primaryStage = (Stage) rootPane.getScene().getWindow();
        CalibrationManager calibrationManager = new CalibrationManager(gazeEstimator, mouseController, primaryStage);

        calibrationManager.startCalibration(() -> Platform.runLater(() -> {
            statusLabel.setText("Статус: Калибровка завершена");

            // АВТОМАТИЧЕСКИ ВКЛЮЧАЕМ УПРАВЛЕНИЕ МЫШЬЮ
            if (!mouseControlEnabled.get()) {
                mouseControlEnabled.set(true);
                mouseController.setEnabled(true);
                mouseController.resetToCenter();

                if (toggleMouseButton != null) {
                    toggleMouseButton.setText("Выкл управление взглядом");
                }

                logger.info("Mouse control automatically enabled after calibration");
            }

            updateButtonStates();

            Point2D calibratedGaze = gazeEstimator.getCalibratedGaze();
            logger.info("Calibration completed. Calibrated gaze: {}", calibratedGaze);
        }));
    }

    @FXML
    private void startTracking() {
        if (cameraManager.isRunning()) {
            logger.warn("Cameras already running");
            statusLabel.setText("Статус: Камеры уже запущены");
            return;
        }

        logger.info("Starting tracking with Face ID: {}, Eye ID: {}", faceCameraId, eyeCameraId);
        statusLabel.setText("Статус: Запуск камер...");

        if (startButton != null) {
            startButton.setDisable(true);
        }

        mouseControlEnabled.set(false);
        processing.set(true);

        new Thread(() -> {
            try {
                Thread.sleep(500);
                cameraManager.startCameras(faceCameraId, eyeCameraId);
            } catch (InterruptedException e) {
                logger.error("Camera start interrupted", e);
                Thread.currentThread().interrupt();
            }
        }).start();

        waitForCameras();
        startDataProcessingThread();
    }

    private void waitForCameras() {
        new Thread(() -> {
            int waitCount = 0;
            int maxWait = 80;

            while (waitCount < maxWait && processing.get()) {
                boolean faceReady = cameraManager.isFaceCameraReady();
                boolean eyeReady = cameraManager.isEyeCameraReady();

                if (faceReady && eyeReady) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Статус: Обе камеры работают");
                        updateButtonStates();
                    });
                    logger.info("Both cameras ready after {} ms", waitCount * 100);
                    return;
                } else if (faceReady || eyeReady) {
                    String status = "Статус: Работает " +
                            (faceReady ? "камера лица" : "камера глаз");
                    Platform.runLater(() -> statusLabel.setText(status));
                }

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                waitCount++;
            }

            if (!cameraManager.isFaceCameraReady() && !cameraManager.isEyeCameraReady()) {
                Platform.runLater(() -> {
                    statusLabel.setText("Статус: Камеры не найдены");
                    updateButtonStates();
                });
                logger.error("No cameras available");
            }
        }, "CameraWaitThread").start();
    }

    /**
     * ОСНОВНОЙ ПОТОК ОБРАБОТКИ ДАННЫХ
     */
    private void startDataProcessingThread() {
        dataProcessingThread = new Thread(() -> {
            try {
                logger.info("Waiting for cameras to stabilize...");
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            logger.info("Data processing thread started with mouse control");

            while (processing.get()) {
                try {
                    // ПОЛУЧАЕМ ОТКАЛИБРОВАННЫЙ ВЗГЛЯД
                    Point2D calibratedGaze = gazeEstimator.getCalibratedGaze();
                    GazeData gazeData = gazeEstimator.getLastGazeData();
                    FaceData faceData = faceProcessor.getLastFaceData();

                    // УПРАВЛЕНИЕ МЫШЬЮ - если включено
                    if (mouseControlEnabled.get() && calibratedGaze != null) {
                        mouseController.updateMousePosition(calibratedGaze, gazeData);
                    }

                    // Обновляем UI с текущими значениями
                    updateUI(calibratedGaze, gazeData, faceData);

                    Thread.sleep(33); // ~30 FPS для плавного движения

                } catch (InterruptedException e) {
                    logger.info("Data processing thread interrupted");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Error in data processing", e);
                }
            }

            logger.info("Data processing thread stopped");
        }, "DataProcessingThread");

        dataProcessingThread.start();
    }

    private void updateUI(Point2D calibratedGaze, GazeData gazeData, FaceData faceData) {
        Platform.runLater(() -> {
            if (calibratedGaze != null) {
                gazeLabel.setText(String.format("Взгляд: X:%.1f Y:%.1f",
                        calibratedGaze.getX(), calibratedGaze.getY()));
            }

            if (gazeData != null) {
                String expression = (gazeData.isLeftEyeClosed() && gazeData.isRightEyeClosed())
                        ? "Моргание" : "Нейтрально";
                expressionLabel.setText("Мимика: " + expression);
            }

            if (faceData != null && faceData.isFaceDetected()) {
                double[] headPose = faceData.getHeadPose();
                if (headPose != null && headPose.length >= 3) {
                    headPoseLabel.setText(String.format("Голова: %.1f° | %.1f° | %.1f°",
                            headPose[0], headPose[1], headPose[2]));
                }
            }
        });
    }

    @FXML
    private void stopTracking() {
        logger.info("Stopping tracking");
        statusLabel.setText("Статус: Трекинг остановлен");

        mouseControlEnabled.set(false);
        processing.set(false);

        if (mouseController != null) {
            mouseController.setEnabled(false);
        }

        if (cameraManager != null) {
            if (gazeEstimator != null) {
                gazeEstimator.stopNeuralMode();
            }
            cameraManager.stopCameras();
        }

        if (dataProcessingThread != null) {
            dataProcessingThread.interrupt();
            try {
                dataProcessingThread.join(2000);
            } catch (InterruptedException e) {
                logger.error("Error stopping data processing thread", e);
                Thread.currentThread().interrupt();
            }
        }

        Platform.runLater(() -> {
            camera1View.setImage(null);
            camera2View.setImage(null);
            updateButtonStates();

            // Сбрасываем метки
            headPoseLabel.setText("Голова: 0° | 0° | 0°");
            gazeLabel.setText("Взгляд: X:0 Y:0");
            expressionLabel.setText("Мимика: Нейтрально");
        });
    }

    @FXML
    private void toggleMouseControl() {
        if (!gazeEstimator.isCalibrated()) {
            showAlert("Калибровка не выполнена",
                    "Сначала выполните калибровку взгляда");
            return;
        }

        boolean newState = !mouseControlEnabled.get();
        mouseControlEnabled.set(newState);
        mouseController.setEnabled(newState);

        String status = newState ? "включено" : "выключено";
        statusLabel.setText("Статус: Управление взглядом " + status);

        if (toggleMouseButton != null) {
            toggleMouseButton.setText(newState ? "Выкл управление взглядом" : "Вкл управление взглядом");
        }

        if (newState) {
            mouseController.resetToCenter();
        }

        logger.info("Gaze control {}", status);
    }

    @FXML
    private void resetMousePosition() {
        if (mouseController != null) {
            mouseController.resetToCenter();
            statusLabel.setText("Статус: Курсор в центре");
            logger.info("Mouse reset to center");
        }
    }

    @FXML
    private void toggleKeyboard() {
        keyboardEnabled = !keyboardEnabled;
        if (keyboardController != null) {
            keyboardController.setKeyboardVisible(keyboardEnabled, keyboardCanvas);
        }
        keyboardCanvas.setVisible(keyboardEnabled);
        statusLabel.setText("Статус: Клавиатура " + (keyboardEnabled ? "показана" : "скрыта"));
        logger.info("Keyboard {}", keyboardEnabled ? "shown" : "hidden");
    }

    @FXML
    private void copyText() {
        if (keyboardController != null) {
            keyboardController.copyToClipboard();
            statusLabel.setText("Статус: Текст скопирован");
            logger.info("Text copied to clipboard");
        }
    }

    @FXML
    private void clearText() {
        if (keyboardController != null) {
            keyboardController.clearText();
            textOutputLabel.setText("");
            statusLabel.setText("Статус: Текст очищен");
            logger.info("Text cleared");
        }
    }

    private void showAlert(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
}