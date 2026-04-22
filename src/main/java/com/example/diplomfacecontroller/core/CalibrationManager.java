package com.example.diplomfacecontroller.core;

import com.example.diplomfacecontroller.input.MouseController;
import javafx.animation.*;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
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
    private static final int POINT_DURATION_SEC = 4;          // сбор на точку 4 секунды
    private static final long IGNORE_FIRST_NS = 800_000_000L; // игнорировать первые 0.8 сек

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

        // Анимация пульсации
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
                // Пропускаем первые IGNORE_FIRST_NS наносекунд
                if (now - startTime < IGNORE_FIRST_NS) return;

                if (now - lastSample >= 70_000_000) { // ~14 Гц
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
                // Используем медиану вместо среднего
                Point2D median = medianFiltered(rawSamples);
                samples.add(new CalibrationSample(median.getX(), median.getY(), screenX, screenY));
                logger.info("Point {} done, raw samples={}, median=({}, {})",
                        index, rawSamples.size(), median.getX(), median.getY());
            } else {
                logger.warn("No samples collected for point {}", index);
            }
            runNextPoint(index + 1);
        });
        pause.play();
    }

    /** Медианная фильтрация (устойчива к выбросам) */
    private Point2D medianFiltered(List<Point2D> samples) {
        if (samples.isEmpty()) return new Point2D(0, 0);
        List<Double> xs = samples.stream().map(Point2D::getX).sorted().collect(Collectors.toList());
        List<Double> ys = samples.stream().map(Point2D::getY).sorted().collect(Collectors.toList());
        return new Point2D(xs.get(xs.size() / 2), ys.get(ys.size() / 2));
    }

    private void finishCalibration() {
        calibrationStage.close();
        if (samples.size() < 3) {
            logger.error("Not enough samples, calibration aborted");
            if (onComplete != null) onComplete.run();
            return;
        }

        // Выбор типа калибровки: квадратичная (рекомендуется) или линейная
        boolean useQuadratic = true;
        if (useQuadratic) {
            GazeEstimator.QuadraticCalibrationParameters params = calculateQuadraticParams(samples);
            gazeEstimator.setQuadraticCalibrationParams(params);
            logger.info("Quadratic calibration params: {}", params);
        } else {
            GazeEstimator.CalibrationParameters params = calculateLinearParams(samples);
            gazeEstimator.setCalibrationParams(params);
            logger.info("Linear calibration params: {}", params);
        }

        mouseController.setEnabled(true);
        mouseController.resetToCenter();
        logger.info("Calibration finished");
        if (onComplete != null) onComplete.run();
    }

    private void cancelCalibration() {
        logger.info("Calibration cancelled");
        calibrationStage.close();
        if (onComplete != null) onComplete.run();
    }

    /** Линейная калибровка (исходная) */
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

    /** Квадратичная калибровка: target = a*raw^2 + b*raw + c */
    private GazeEstimator.QuadraticCalibrationParameters calculateQuadraticParams(List<CalibrationSample> samples) {
        int n = samples.size();
        double sw = screenBounds.getWidth();
        double sh = screenBounds.getHeight();

        // Собираем коэффициенты для систем уравнений (метод наименьших квадратов)
        // Для X: sum_{i} (a*rawX_i^2 + b*rawX_i + c - targetX_i)^2 -> min
        // Система:
        // [ sum(raw^4) sum(raw^3) sum(raw^2) ] [a] = [ sum(raw^2 * target) ]
        // [ sum(raw^3) sum(raw^2) sum(raw)   ] [b]   [ sum(raw * target)   ]
        // [ sum(raw^2) sum(raw)   n          ] [c]   [ sum(target)         ]
        double sumX4 = 0, sumX3 = 0, sumX2 = 0, sumX1 = 0;
        double sumX2T = 0, sumX1T = 0, sumT = 0;
        double sumY4 = 0, sumY3 = 0, sumY2 = 0, sumY1 = 0;
        double sumY2T = 0, sumY1T = 0;

        for (CalibrationSample s : samples) {
            double rawX = s.rawX;
            double rawY = s.rawY;
            double targetX = (s.targetX / sw) * 2 - 1;
            double targetY = (s.targetY / sh) * 2 - 1;

            double x2 = rawX * rawX;
            double x3 = x2 * rawX;
            double x4 = x2 * x2;
            sumX4 += x4;
            sumX3 += x3;
            sumX2 += x2;
            sumX1 += rawX;
            sumX2T += x2 * targetX;
            sumX1T += rawX * targetX;
            sumT += targetX;

            double y2 = rawY * rawY;
            double y3 = y2 * rawY;
            double y4 = y2 * y2;
            sumY4 += y4;
            sumY3 += y3;
            sumY2 += y2;
            sumY1 += rawY;
            sumY2T += y2 * targetY;
            sumY1T += rawY * targetY;
        }

        // Решаем системы методом Крамера (или можно использовать Apache Commons Math)
        double[] coeffX = solveCubic(sumX4, sumX3, sumX2, sumX3, sumX2, sumX1, sumX2, sumX1, n,
                sumX2T, sumX1T, sumT);
        double[] coeffY = solveCubic(sumY4, sumY3, sumY2, sumY3, sumY2, sumY1, sumY2, sumY1, n,
                sumY2T, sumY1T, sumT); // sumT для Y тоже используется (сумма targetY)

        return new GazeEstimator.QuadraticCalibrationParameters(coeffX[0], coeffX[1], coeffX[2],
                coeffY[0], coeffY[1], coeffY[2]);
    }

    /** Решение системы 3x3 методом Гаусса (возвращает [a,b,c]) */
    private double[] solveCubic(double a11, double a12, double a13,
                                double a21, double a22, double a23,
                                double a31, double a32, double a33,
                                double b1, double b2, double b3) {
        double[][] A = {{a11, a12, a13}, {a21, a22, a23}, {a31, a32, a33}};
        double[] B = {b1, b2, b3};
        int n = 3;
        for (int i = 0; i < n; i++) {
            int max = i;
            for (int j = i + 1; j < n; j++)
                if (Math.abs(A[j][i]) > Math.abs(A[max][i])) max = j;
            double[] temp = A[i]; A[i] = A[max]; A[max] = temp;
            double t = B[i]; B[i] = B[max]; B[max] = t;
            for (int j = i + 1; j < n; j++) {
                double factor = A[j][i] / A[i][i];
                B[j] -= factor * B[i];
                for (int k = i; k < n; k++)
                    A[j][k] -= factor * A[i][k];
            }
        }
        double[] X = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double sum = 0;
            for (int j = i + 1; j < n; j++)
                sum += A[i][j] * X[j];
            X[i] = (B[i] - sum) / A[i][i];
        }
        return X; // [a, b, c]
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