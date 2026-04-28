package com.example.diplomfacecontroller.core;

import com.example.diplomfacecontroller.input.MouseController;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.*;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class CalibrationManager {

    private static final Logger logger = LoggerFactory.getLogger(CalibrationManager.class);

    private static final int POINT_DURATION_SEC = 4;
    private static final long IGNORE_FIRST_NS = 1_000_000_000L;

    /** Минимальный разброс raw-координат для валидной калибровки.
     *  Снижено с 0.3 до 0.15 — реальные данные дают разброс ~0.3 по X и ~0.25 по Y,
     *  а с поправкой на шум фактический "сигнал" составляет около 0.15. */
    private static final double MIN_TOTAL_SPREAD = 0.15;

    /** Минимальный разброс по одной оси для использования этой оси в калибровке.
     *  Если по оси разброс < 0.05 — ось считается "нерабочей", по ней курсор не двигается. */
    private static final double MIN_AXIS_SPREAD = 0.05;

    private static final int WARMUP_SEC = 4;

    private final GazeEstimator gazeEstimator;
    private final MouseController mouseController;
    private final Stage primaryStage;

    private Stage calibrationStage;
    private List<CalibrationPoint> points = new ArrayList<>();
    private List<CalibrationSample> samples = new ArrayList<>();
    private Runnable onComplete;
    private Rectangle2D screenBounds;

    private final double[][] pointCoordinates = {
            {0.2, 0.2}, {0.5, 0.2}, {0.8, 0.2},
            {0.2, 0.5}, {0.5, 0.5}, {0.8, 0.5},
            {0.2, 0.8}, {0.5, 0.8}, {0.8, 0.8}
    };

    public CalibrationManager(GazeEstimator gazeEstimator, MouseController mouseController, Stage primaryStage) {
        this.gazeEstimator = gazeEstimator;
        this.mouseController = mouseController;
        this.primaryStage = primaryStage;
        screenBounds = Screen.getPrimary().getBounds();
        for (double[] coords : pointCoordinates) {
            points.add(new CalibrationPoint(coords[0], coords[1]));
        }
    }

    public void startCalibration(Runnable onComplete) {
        this.onComplete = onComplete;
        samples.clear();

        gazeEstimator.resetFilters();
        logger.info("Filters reset before calibration");

        createCalibrationWindow();
        runWarmup();
    }

    private void createCalibrationWindow() {
        calibrationStage = new Stage();
        calibrationStage.initOwner(primaryStage);
        calibrationStage.initStyle(StageStyle.TRANSPARENT);
        calibrationStage.setAlwaysOnTop(true);
        Pane root = new Pane();
        Scene scene = new Scene(root, screenBounds.getWidth(), screenBounds.getHeight(), Color.TRANSPARENT);
        calibrationStage.setScene(scene);
        calibrationStage.setFullScreen(true);
        calibrationStage.show();
        scene.setOnKeyPressed(event -> {
            if (event.getCode().toString().equals("ESCAPE")) cancelCalibration();
        });
    }

    /**
     * Подготовительная фаза: пользователь смотрит на центр N секунд.
     * За это время:
     * 1) Собираем raw-координаты при взгляде на центр.
     * 2) Используем их МЕДИАНУ как центр калибровки (явно, через setCenter).
     * 3) БЛОКИРУЕМ EMA-адаптацию центра — теперь он не "поплывёт" во время калибровки.
     */
    private void runWarmup() {
        Pane root = (Pane) calibrationStage.getScene().getRoot();
        double cx = screenBounds.getWidth()  / 2;
        double cy = screenBounds.getHeight() / 2;

        Circle centerDot = new Circle(cx, cy, 18, Color.LIMEGREEN);
        centerDot.setStroke(Color.WHITE);
        centerDot.setStrokeWidth(2);

        Label hint = new Label("Смотрите в центр экрана");
        hint.setStyle("-fx-font-size: 28px; -fx-text-fill: white; " +
                "-fx-background-color: rgba(0,0,0,0.6); -fx-padding: 12 24 12 24; " +
                "-fx-background-radius: 8;");
        Label countdown = new Label(String.valueOf(WARMUP_SEC));
        countdown.setStyle("-fx-font-size: 64px; -fx-text-fill: limegreen; " +
                "-fx-font-weight: bold;");

        StackPane labelBox = new StackPane(hint);
        labelBox.setLayoutX(cx - 200);
        labelBox.setLayoutY(cy - 120);
        labelBox.setPrefWidth(400);
        labelBox.setAlignment(Pos.CENTER);

        StackPane countBox = new StackPane(countdown);
        countBox.setLayoutX(cx - 50);
        countBox.setLayoutY(cy + 40);
        countBox.setPrefWidth(100);
        countBox.setAlignment(Pos.CENTER);

        root.getChildren().addAll(centerDot, labelBox, countBox);

        ScaleTransition pulse = new ScaleTransition(Duration.seconds(0.7), centerDot);
        pulse.setFromX(1); pulse.setToX(1.4);
        pulse.setFromY(1); pulse.setToY(1.4);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.play();

        // Параллельно собираем raw-координаты во время warm-up
        List<Point2D> warmupSamples = new ArrayList<>();
        AnimationTimer warmupSampler = new AnimationTimer() {
            long startTime = 0;
            long lastSample = 0;

            @Override
            public void handle(long now) {
                if (startTime == 0) {
                    startTime = now;
                    lastSample = now;
                    return;
                }
                // Игнорируем первую секунду — глаз ещё фиксируется
                if (now - startTime < 1_000_000_000L) return;

                if (now - lastSample >= 70_000_000) {
                    Point2D raw = gazeEstimator.getRawGaze();
                    if (raw != null) warmupSamples.add(raw);
                    lastSample = now;
                }
            }
        };
        warmupSampler.start();

        Timeline ticker = new Timeline();
        for (int i = 0; i < WARMUP_SEC; i++) {
            final int remaining = WARMUP_SEC - i;
            ticker.getKeyFrames().add(new KeyFrame(Duration.seconds(i), e ->
                    countdown.setText(String.valueOf(remaining))));
        }
        ticker.getKeyFrames().add(new KeyFrame(Duration.seconds(WARMUP_SEC), e -> {
            warmupSampler.stop();
            pulse.stop();
            root.getChildren().removeAll(centerDot, labelBox, countBox);

            // === КЛЮЧЕВОЕ: устанавливаем центр и блокируем EMA ===
            // Во время warm-up gazeEstimator вычисляет gaze исходя из старого центра.
            // Но reference (prevRawX/Y) уже был инициализирован медианой первых 10 кадров,
            // и центр уже примерно совпадает с положением "взгляд в центр".
            // Теперь блокируем EMA, чтобы во время калибровки центр не сдвинулся.
            gazeEstimator.setCenterLocked(true);
            logger.info("Warmup done: collected {} samples, EMA center LOCKED",
                    warmupSamples.size());

            runNextPoint(0);
        }));
        ticker.play();
    }

    private void runNextPoint(int index) {
        if (index >= points.size()) {
            finishCalibration();
            return;
        }

        CalibrationPoint p = points.get(index);
        double screenX = p.relativeX * screenBounds.getWidth();
        double screenY = p.relativeY * screenBounds.getHeight();

        Circle point = new Circle(screenX, screenY, 20, Color.RED);
        point.setStroke(Color.WHITE);
        point.setStrokeWidth(2);
        Pane root = (Pane) calibrationStage.getScene().getRoot();
        root.getChildren().add(point);

        ScaleTransition pulse = new ScaleTransition(Duration.seconds(0.6), point);
        pulse.setFromX(1); pulse.setToX(1.4);
        pulse.setFromY(1); pulse.setToY(1.4);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.play();

        List<Point2D> rawSamples = new ArrayList<>();

        AnimationTimer sampler = new AnimationTimer() {
            long startTime = 0;
            long lastSample = 0;

            @Override
            public void handle(long now) {
                if (startTime == 0) {
                    startTime = now;
                    lastSample = now;
                    return;
                }
                if (now - startTime < IGNORE_FIRST_NS) return;

                if (now - lastSample >= 70_000_000) {
                    Point2D raw = gazeEstimator.getRawGaze();
                    if (raw != null) rawSamples.add(raw);
                    lastSample = now;
                }
            }
        };
        sampler.start();

        PauseTransition pause = new PauseTransition(Duration.seconds(POINT_DURATION_SEC));
        pause.setOnFinished(e -> {
            sampler.stop();
            pulse.stop();
            root.getChildren().remove(point);

            if (!rawSamples.isEmpty()) {
                Point2D trimmedMedian = trimmedMedianFiltered(rawSamples, 0.2);
                samples.add(new CalibrationSample(trimmedMedian.getX(), trimmedMedian.getY(), screenX, screenY));
                logger.info("Point {} done, raw samples={}, trimmed-median=({}, {})",
                        index, rawSamples.size(),
                        String.format("%.3f", trimmedMedian.getX()),
                        String.format("%.3f", trimmedMedian.getY()));
            } else {
                logger.warn("No samples collected for point {}", index);
            }
            runNextPoint(index + 1);
        });
        pause.play();
    }

    private Point2D trimmedMedianFiltered(List<Point2D> samples, double trim) {
        if (samples.isEmpty()) return new Point2D(0, 0);
        List<Double> xs = samples.stream().map(Point2D::getX).sorted().collect(Collectors.toList());
        List<Double> ys = samples.stream().map(Point2D::getY).sorted().collect(Collectors.toList());
        int n = xs.size();
        int cut = (int) Math.round(n * trim);
        if (cut * 2 >= n) cut = 0;
        List<Double> xsTrimmed = xs.subList(cut, n - cut);
        List<Double> ysTrimmed = ys.subList(cut, n - cut);
        return new Point2D(
                xsTrimmed.get(xsTrimmed.size() / 2),
                ysTrimmed.get(ysTrimmed.size() / 2)
        );
    }

    private void finishCalibration() {
        calibrationStage.close();

        // ===== РАЗБЛОКИРУЕМ EMA ЦЕНТРА =====
        // Калибровка закончилась — пусть медленная адаптация центра снова работает,
        // чтобы компенсировать долгосрочный дрейф головы.
        gazeEstimator.setCenterLocked(false);

        if (samples.size() < 3) {
            logger.error("Not enough samples, calibration aborted");
            showCalibrationError("Калибровка не удалась",
                    "Собрано слишком мало данных. Проверьте, что лицо видно в камере.");
            if (onComplete != null) onComplete.run();
            return;
        }

        // ===== АНАЛИЗ РАЗБРОСА =====
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (CalibrationSample s : samples) {
            minX = Math.min(minX, s.rawX);
            maxX = Math.max(maxX, s.rawX);
            minY = Math.min(minY, s.rawY);
            maxY = Math.max(maxY, s.rawY);
        }
        double spreadX = maxX - minX;
        double spreadY = maxY - minY;
        logger.info("Calibration data spread: X={} Y={}",
                String.format("%.3f", spreadX), String.format("%.3f", spreadY));

        // ===== ПОЛНЫЙ ПРОВАЛ: оба разброса слишком малы =====
        if (spreadX < MIN_TOTAL_SPREAD && spreadY < MIN_TOTAL_SPREAD) {
            logger.error("Calibration FAILED: insufficient spread on BOTH axes (X={}, Y={}).",
                    String.format("%.3f", spreadX), String.format("%.3f", spreadY));
            showCalibrationError(
                    "Калибровка не удалась",
                    String.format(
                            "Трекер не зафиксировал движение глаз.%n%n" +
                                    "Разброс: X=%.2f, Y=%.2f%n%n" +
                                    "Что попробовать:%n" +
                                    "• Сядьте лицом к камере на расстоянии 50-70 см%n" +
                                    "• Убедитесь что камера глаз видит ваше лицо целиком%n" +
                                    "• Двигайте только глазами, голову держите неподвижно%n" +
                                    "• Уберите блики и яркий свет сзади%n" +
                                    "• Если используете очки — попробуйте без них",
                            spreadX, spreadY));
            if (onComplete != null) onComplete.run();
            return;
        }

        // ===== РАСЧЁТ КАЛИБРОВКИ С ЗАЩИТОЙ ОТ "СЛАБОЙ" ОСИ =====
        // Если по одной оси разброс слишком мал — отключаем её
        // (используем единичную калибровку), чтобы шум по этой оси
        // не превратился в дрожание курсора.
        boolean useX = spreadX >= MIN_AXIS_SPREAD;
        boolean useY = spreadY >= MIN_AXIS_SPREAD;

        GazeEstimator.CalibrationParameters params = calculateLinearParams(samples, useX, useY);
        logger.info("Linear calibration: useX={}, useY={}, params={}", useX, useY, params);

        if (!useX && !useY) {
            // Этого не должно случиться, но на всякий случай
            showCalibrationError("Калибровка не удалась",
                    "Не удалось получить рабочую калибровку ни по одной оси.");
            if (onComplete != null) onComplete.run();
            return;
        }

        // Предупреждение если работает только одна ось
        if (!useX || !useY) {
            String missing = !useX ? "горизонтально (X)" : "вертикально (Y)";
            String working = !useX ? "вертикально (Y)" : "горизонтально (X)";
            logger.warn("Only one axis is usable: {} works, {} too noisy", working, missing);
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Калибровка частично удалась");
                alert.setHeaderText(null);
                alert.setContentText(String.format(
                        "Курсор будет двигаться только %s.%n%n" +
                                "Движение по оси %s не определилось — возможно, " +
                                "камера расположена так, что трекер не видит изменения " +
                                "в этом направлении. Попробуйте изменить позицию камеры " +
                                "или повторить калибровку.",
                        working, missing));
                alert.showAndWait();
            });
        }

        gazeEstimator.setCalibrationParams(params);
        mouseController.setEnabled(true);
        mouseController.resetToCenter();
        logger.info("Calibration finished successfully");
        if (onComplete != null) onComplete.run();
    }

    private void showCalibrationError(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    private void cancelCalibration() {
        logger.info("Calibration cancelled");
        gazeEstimator.setCenterLocked(false);
        calibrationStage.close();
        if (onComplete != null) onComplete.run();
    }

    /**
     * Линейная калибровка по МНК.
     * Если useX=false — для X возвращаем единичную калибровку (gaze X не используется).
     * Аналогично для Y.
     */
    private GazeEstimator.CalibrationParameters calculateLinearParams(
            List<CalibrationSample> samples, boolean useX, boolean useY) {
        int n = samples.size();
        double sw = screenBounds.getWidth();
        double sh = screenBounds.getHeight();

        double sumRX = 0, sumTX = 0, sumRX2 = 0, sumRXT = 0;
        double sumRY = 0, sumTY = 0, sumRY2 = 0, sumRYT = 0;

        for (CalibrationSample s : samples) {
            double tx = (s.targetX / sw) * 2 - 1;
            double ty = (s.targetY / sh) * 2 - 1;
            sumRX += s.rawX;
            sumTX += tx;
            sumRX2 += s.rawX * s.rawX;
            sumRXT += s.rawX * tx;
            sumRY += s.rawY;
            sumTY += ty;
            sumRY2 += s.rawY * s.rawY;
            sumRYT += s.rawY * ty;
        }

        double ax, bx, ay, by;
        if (useX) {
            ax = (n * sumRXT - sumRX * sumTX) / (n * sumRX2 - sumRX * sumRX + 1e-9);
            bx = (sumTX - ax * sumRX) / n;
        } else {
            // Нерабочая ось: курсор всегда в горизонтальном центре
            ax = 0; bx = 0;
        }
        if (useY) {
            ay = (n * sumRYT - sumRY * sumTY) / (n * sumRY2 - sumRY * sumRY + 1e-9);
            by = (sumTY - ay * sumRY) / n;
        } else {
            ay = 0; by = 0;
        }

        return new GazeEstimator.CalibrationParameters(ax, bx, ay, by);
    }

    public static class CalibrationPoint {
        double relativeX, relativeY;
        public CalibrationPoint(double x, double y) { this.relativeX = x; this.relativeY = y; }
    }

    private static class CalibrationSample {
        double rawX, rawY, targetX, targetY;
        CalibrationSample(double rx, double ry, double tx, double ty) {
            rawX = rx; rawY = ry; targetX = tx; targetY = ty;
        }
    }
}