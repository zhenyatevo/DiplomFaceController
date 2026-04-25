package com.example.diplomfacecontroller.core;

import com.example.diplomfacecontroller.input.MouseController;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.layout.Pane;
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

    private static final int POINT_DURATION_SEC = 5;
    private static final long IGNORE_FIRST_NS = 1_200_000_000L;

    /** Минимальный требуемый разброс raw-координат между точками калибровки.
     *  Если разброс меньше — калибровка не имеет смысла (взгляд почти не двигался). */
    private static final double MIN_RAW_SPREAD = 0.3;

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
        createCalibrationWindow();
        runNextPoint(0);
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

    /** Trimmed median — отбрасываем верхние/нижние `trim` % значений, потом медиана. */
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
        if (samples.size() < 3) {
            logger.error("Not enough samples, calibration aborted");
            showCalibrationError("Калибровка не удалась",
                    "Собрано слишком мало данных. Проверьте, что лицо видно в камере.");
            if (onComplete != null) onComplete.run();
            return;
        }

        // ===== ПРОВЕРКА КАЧЕСТВА КАЛИБРОВКИ =====
        // Если все точки дали почти одинаковые raw-координаты, значит трекинг
        // не работает или не успел инициализироваться. Применять такую калибровку
        // НЕЛЬЗЯ — она сломает курсор (как раз твой случай: rawX = -1.5 везде).
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

        if (spreadX < MIN_RAW_SPREAD || spreadY < MIN_RAW_SPREAD) {
            logger.error("Calibration FAILED: insufficient spread (X={}, Y={}). " +
                            "Eye tracking is not picking up gaze movement.",
                    String.format("%.3f", spreadX), String.format("%.3f", spreadY));
            showCalibrationError(
                    "Калибровка не удалась",
                    String.format(
                            "Трекер не зафиксировал движение глаз между точками калибровки.%n%n" +
                                    "Разброс: X=%.2f, Y=%.2f (требуется минимум %.1f).%n%n" +
                                    "Возможные причины:%n" +
                                    "• Программа не успела инициализировать трекер (подождите 2-3 секунды и повторите)%n" +
                                    "• Камера глаз не видит лицо%n" +
                                    "• MediaPipe вернул некорректные данные на старте%n%n" +
                                    "Старая калибровка сохранена.",
                            spreadX, spreadY, MIN_RAW_SPREAD));
            if (onComplete != null) onComplete.run();
            return;
        }

        // ===== ЛИНЕЙНАЯ КАЛИБРОВКА =====
        GazeEstimator.CalibrationParameters params = calculateLinearParams(samples);

        // Дополнительная проверка: коэффициенты не должны быть нулевыми
        if (Math.abs(params.getAx()) < 0.5 || Math.abs(params.getAy()) < 0.5) {
            logger.error("Calibration FAILED: degenerate coefficients ({})", params);
            showCalibrationError(
                    "Калибровка не удалась",
                    "Получены неадекватные коэффициенты калибровки.\n" +
                            "Подождите 2-3 секунды после запуска трекинга и повторите.");
            if (onComplete != null) onComplete.run();
            return;
        }

        gazeEstimator.setCalibrationParams(params);
        logger.info("Linear calibration params: {}", params);

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
        calibrationStage.close();
        if (onComplete != null) onComplete.run();
    }

    private GazeEstimator.CalibrationParameters calculateLinearParams(List<CalibrationSample> samples) {
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

        double ax = (n * sumRXT - sumRX * sumTX) / (n * sumRX2 - sumRX * sumRX + 1e-9);
        double bx = (sumTX - ax * sumRX) / n;
        double ay = (n * sumRYT - sumRY * sumTY) / (n * sumRY2 - sumRY * sumRY + 1e-9);
        double by = (sumTY - ay * sumRY) / n;

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