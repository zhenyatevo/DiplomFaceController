package com.example.diplomfacecontroller.core;

import com.example.diplomfacecontroller.models.GazeData;
import javafx.geometry.Point2D;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;
import org.opencv.objdetect.CascadeClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class GazeEstimator {
    private static final Logger logger = LoggerFactory.getLogger(GazeEstimator.class);

    // ===== Haar (старый режим — fallback) =====
    private CascadeClassifier eyeDetector;
    private boolean detectorLoaded;

    // ===== MediaPipe (новый режим) =====
    private MediaPipeBridge    mediaPipeBridge;
    private IrisFeatureExtractor irisExtractor;
    private boolean            neuralModeActive = false;

    // ===== Общие поля =====
    private long   lastBlinkTime;
    private int    blinkCount;
    private Point2D rawGaze;
    private GazeData lastGazeData;

    private int frameCount       = 0;
    private int eyesFoundCount   = 0;
    private int eyesNotFoundCount = 0;

    // Отбраковка выбросов (Haar-режим)
    private double prevLeftX = 0, prevLeftY = 0;
    private double prevRightX = 0, prevRightY = 0;
    private static final double MAX_GAZE_STEP = 0.3;

    // Адаптивное сглаживание (оба режима)
    private double prevX = 0, prevY = 0;

    // ===== Калибровка =====
    private CalibrationParameters          calibrationParams;
    private QuadraticCalibrationParameters quadParams;
    private boolean useQuadratic = false;
    private final double[] histX = new double[5];
    private final double[] histY = new double[5];
    private int histIdx = 0;

    // ===== Допустимый диапазон калиброванного gaze =====
    // РАСШИРЕНО с ±1.2 до ±1.5 — чтобы MouseController мог применить edgeGain
    // и реально докрутить курсор до краёв экрана.
    private static final double CALIB_CLAMP = 1.5;

    // Добавь метод:
    private Point2D medianFilter(double x, double y) {
        histX[histIdx] = x;
        histY[histIdx] = y;
        histIdx = (histIdx + 1) % histX.length;

        double[] sx = histX.clone();
        double[] sy = histY.clone();
        java.util.Arrays.sort(sx);
        java.util.Arrays.sort(sy);
        return new Point2D(sx[sx.length / 2], sy[sy.length / 2]);
    }

    public GazeEstimator() {
        this.rawGaze          = new Point2D(0, 0);
        this.calibrationParams = new CalibrationParameters(1, 0, 1, 0);
        this.lastGazeData     = new GazeData();
        this.lastBlinkTime    = System.currentTimeMillis();
        this.irisExtractor    = new IrisFeatureExtractor();
        initDetector();
    }

    // ================================================================
    //  ИНИЦИАЛИЗАЦИЯ
    // ================================================================

    private void initDetector() {
        try {
            eyeDetector   = new CascadeClassifier();
            detectorLoaded = loadCascade(eyeDetector, "/cascades/haarcascade_eye.xml", "eye");
            if (detectorLoaded) logger.info("Eye detector loaded successfully");
            else                logger.error("Failed to load eye detector");
        } catch (Exception e) {
            logger.error("Failed to load eye detector: {}", e.getMessage(), e);
        }
    }

    private boolean loadCascade(CascadeClassifier detector, String resourcePath, String name) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                logger.error("{} cascade not found: {}", name, resourcePath);
                return false;
            }
            File tmp = File.createTempFile("opencv_" + name + "_", ".xml");
            tmp.deleteOnExit();
            Files.copy(is, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return detector.load(tmp.getAbsolutePath());
        } catch (Exception e) {
            logger.error("Error loading {} cascade: {}", name, e.getMessage());
            return false;
        }
    }

    // ================================================================
    //  НЕЙРОННЫЙ РЕЖИМ (MediaPipe)
    // ================================================================

    /**
     * Запустить MediaPipe bridge. Вызывать из MainController.initializeComponents()
     * в отдельном потоке, т.к. старт занимает ~4 секунды.
     */
    public void startNeuralMode() {
        new Thread(() -> {
            try {
                mediaPipeBridge = new MediaPipeBridge();
                mediaPipeBridge.start();
                neuralModeActive = true;
                logger.info("Neural gaze mode ACTIVE");
            } catch (Exception e) {
                logger.error("Failed to start neural mode, falling back to Haar: {}", e.getMessage());
                neuralModeActive = false;
            }
        }, "NeuralModeStartThread").start();
    }

    /**
     * Остановить MediaPipe bridge. Вызывать из stopTracking().
     */
    public void stopNeuralMode() {
        neuralModeActive = false;
        if (mediaPipeBridge != null) {
            mediaPipeBridge.stop();
            mediaPipeBridge = null;
        }
        logger.info("Neural gaze mode stopped");
    }

    public boolean isNeuralModeActive() {
        return neuralModeActive && mediaPipeBridge != null && mediaPipeBridge.isConnected();
    }

    // ================================================================
    //  ОСНОВНОЙ МЕТОД АНАЛИЗА — вызывается из CameraManager
    //  Автоматически выбирает нейронный или Haar-режим
    // ================================================================

    public GazeData analyzeGaze(Mat frame) {
        if (isNeuralModeActive()) {
            return analyzeGazeNeural(frame);
        } else {
            return analyzeGazeHaar(frame);
        }
    }

    // ================================================================
    //  НЕЙРОННЫЙ АНАЛИЗ
    // ================================================================

    private GazeData analyzeGazeNeural(Mat frame) {
        frameCount++;
        GazeData gd = new GazeData();

        float[] landmarks = mediaPipeBridge.processFrame(frame);
        if (landmarks == null) {
            return lastGazeData;
        }

        float[] iris = irisExtractor.extract(landmarks);

        // Среднее между двумя зрачками
        double rawX = (iris[0] + iris[2]) / 2.0;
        double rawY = (iris[1] + iris[3]) / 2.0;

        // ===== РАСШИРЕННЫЕ ДИАПАЗОНЫ =====
        // ВАЖНО: если в твоих логах видно, что rawX реально ходит от 0.46 до 0.70,
        // настрой centerX = (max+min)/2 = 0.58, rangeX = (max-min)/2 = 0.12.
        // Старые значения (0.10, 0.045) были слишком УЗКИМИ — нормированный gazeX
        // выходил за ±1 при обычных поворотах глаза и обрезался, из-за чего
        // калибровка не могла вытянуть края.
        //
        // Теперь даём больше запаса, чтобы даже при экстремальном взгляде
        // нормированное значение было около ±1, а не прибивалось к клампу.
        double centerX = 0.58;
        double centerY = 0.44;
        double rangeX  = 0.13;   // было 0.10 — расширено
        double rangeY  = 0.065;  // было 0.045 — расширено

        double gazeX = (rawX - centerX) / rangeX;
        double gazeY = (rawY - centerY) / rangeY;

        // ===== НЕ КЛАМПИМ ЖЁСТКО ДО КАЛИБРОВКИ =====
        // Мягкое ограничение, чтобы выбросы не ломали медианный фильтр,
        // но диапазон шире ±1, чтобы калибровка могла учесть крайние точки.
        gazeX = Math.max(-1.5, Math.min(1.5, gazeX));
        gazeY = Math.max(-1.5, Math.min(1.5, gazeY));

        // Сглаживание
        Point2D filtered = medianFilter(gazeX, gazeY);
        this.rawGaze = smooth(filtered);

        gd.setLeftEyeGaze(new Point2D(iris[0] * 2 - 1, iris[1] * 2 - 1));
        gd.setRightEyeGaze(new Point2D(iris[2] * 2 - 1, iris[3] * 2 - 1));
        gd.setCombinedGaze(rawGaze);

        boolean leftClosed  = iris[4] < 0.18f;
        boolean rightClosed = iris[5] < 0.18f;
        gd.setLeftEyeClosed(leftClosed);
        gd.setRightEyeClosed(rightClosed);

        if (leftClosed && rightClosed) {
            long now = System.currentTimeMillis();
            if (now - lastBlinkTime > 150) {
                blinkCount++;
                gd.setLastBlinkTime(now);
                lastBlinkTime = now;
            }
        }

        if (frameCount % 30 == 0) {
            logger.info("[Neural] rawIris=({},{}) gaze=({},{}) EAR L={} R={}",
                    String.format("%.3f", rawX), String.format("%.3f", rawY),
                    String.format("%.3f", gazeX), String.format("%.3f", gazeY),
                    String.format("%.2f", iris[4]), String.format("%.2f", iris[5]));
        }

        this.lastGazeData = gd;
        return gd;
    }

    // ================================================================
    //  HAAR АНАЛИЗ (fallback)
    // ================================================================

    private GazeData analyzeGazeHaar(Mat frame) {
        frameCount++;
        GazeData gazeData = new GazeData();

        if (!detectorLoaded || frame == null || frame.empty()) {
            this.rawGaze = new Point2D(0, 0);
            gazeData.setCombinedGaze(new Point2D(0, 0));
            this.lastGazeData = gazeData;
            return gazeData;
        }

        try {
            Mat gray = new Mat();
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.equalizeHist(gray, gray);

            MatOfRect eyesRect = new MatOfRect();
            eyeDetector.detectMultiScale(gray, eyesRect,
                    1.1, 4, 0, new Size(20, 20), new Size());
            Rect[] eyesArray = eyesRect.toArray();

            if (eyesArray.length == 0) {
                eyesNotFoundCount++;
                this.rawGaze = new Point2D(0, 0);
                gazeData.setCombinedGaze(new Point2D(0, 0));
                this.lastGazeData = gazeData;
                return gazeData;
            }
            eyesFoundCount++;

            for (int i = 0; i < Math.min(eyesArray.length, 2); i++) {
                Rect eye = eyesArray[i];
                Mat eyeROI = gray.submat(eye);
                Point pupil = findPupilCenter(eyeROI);

                double rawGazeX = (pupil.x - eye.width / 2.0) / (eye.width / 2.0);
                double rawGazeY = (pupil.y - eye.height / 2.0) / (eye.height / 2.0);
                double finalX = rawGazeX, finalY = rawGazeY;

                if (i == 0) {
                    if (Math.abs(rawGazeX - prevLeftX) > MAX_GAZE_STEP ||
                            Math.abs(rawGazeY - prevLeftY) > MAX_GAZE_STEP) {
                        finalX = prevLeftX; finalY = prevLeftY;
                    } else { prevLeftX = rawGazeX; prevLeftY = rawGazeY; }
                    gazeData.setLeftEyeGaze(new Point2D(finalX, finalY));
                } else if (i == 1) {
                    if (Math.abs(rawGazeX - prevRightX) > MAX_GAZE_STEP ||
                            Math.abs(rawGazeY - prevRightY) > MAX_GAZE_STEP) {
                        finalX = prevRightX; finalY = prevRightY;
                    } else { prevRightX = rawGazeX; prevRightY = rawGazeY; }
                    gazeData.setRightEyeGaze(new Point2D(finalX, finalY));
                }
            }

            detectBlink(gazeData, eyesArray.length);

            Point2D combined = computeCombinedGaze(gazeData);
            if (combined != null) {
                this.rawGaze = smooth(combined);
                gazeData.setCombinedGaze(combined);
            } else {
                this.rawGaze = new Point2D(0, 0);
                gazeData.setCombinedGaze(new Point2D(0, 0));
            }
            this.lastGazeData = gazeData;

        } catch (Exception e) {
            logger.error("Error in analyzeGazeHaar: {}", e.getMessage());
            this.rawGaze = new Point2D(0, 0);
            gazeData.setCombinedGaze(new Point2D(0, 0));
            this.lastGazeData = gazeData;
        }
        return gazeData;
    }

    private Point findPupilCenter(Mat eyeROI) {
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(eyeROI, blurred, new Size(5, 5), 0);
        Mat median = new Mat();
        Imgproc.medianBlur(blurred, median, 5);
        Mat thresh = new Mat();
        Imgproc.adaptiveThreshold(median, thresh, 255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, 11, 2);
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
        Imgproc.morphologyEx(thresh, thresh, Imgproc.MORPH_CLOSE, kernel);

        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(thresh, contours, new Mat(),
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        double maxArea = 0;
        MatOfPoint best = null;
        for (MatOfPoint c : contours) {
            double area = Imgproc.contourArea(c);
            if (area > maxArea && area > 10) { maxArea = area; best = c; }
        }
        if (best != null) {
            Moments m = Imgproc.moments(best);
            if (m.get_m00() != 0) {
                int cx = (int)(m.get_m10() / m.get_m00());
                int cy = (int)(m.get_m01() / m.get_m00());
                if (cx > 5 && cx < eyeROI.width()-5 && cy > 5 && cy < eyeROI.height()-5)
                    return new Point(cx, cy);
            }
        }
        return new Point(eyeROI.width() / 2.0, eyeROI.height() / 2.0);
    }

    private void detectBlink(GazeData gazeData, int eyesFound) {
        long now = System.currentTimeMillis();
        if (eyesFound < 2) {
            gazeData.setLeftEyeClosed(true);
            gazeData.setRightEyeClosed(true);
            if (now - lastBlinkTime > 100) {
                blinkCount++;
                gazeData.setLastBlinkTime(now);
                lastBlinkTime = now;
            }
        } else {
            gazeData.setLeftEyeClosed(false);
            gazeData.setRightEyeClosed(false);
        }
        gazeData.setBlinkRate((blinkCount * 60000.0) / (now - lastBlinkTime + 1));
    }

    private Point2D computeCombinedGaze(GazeData gd) {
        Point2D l = gd.getLeftEyeGaze(), r = gd.getRightEyeGaze();
        if (l == null && r == null) return new Point2D(0, 0);
        if (l == null) return r;
        if (r == null) return l;
        return new Point2D(
                Math.max(-1, Math.min(1, (l.getX() + r.getX()) / 2.0)),
                Math.max(-1, Math.min(1, (l.getY() + r.getY()) / 2.0))
        );
    }

    // ================================================================
    //  СГЛАЖИВАНИЕ И КАЛИБРОВКА
    // ================================================================

    private Point2D smooth(Point2D raw) {
        double dx    = Math.abs(raw.getX() - prevX);
        double dy    = Math.abs(raw.getY() - prevY);
        double speed = Math.sqrt(dx * dx + dy * dy);
        double alpha = Math.min(0.4, Math.max(0.05, 0.15 / (speed + 0.1)));
        double sx    = prevX + alpha * (raw.getX() - prevX);
        double sy    = prevY + alpha * (raw.getY() - prevY);
        prevX = sx; prevY = sy;
        return new Point2D(sx, sy);
    }

    public Point2D getRawGaze() { return rawGaze; }

    public Point2D getCalibratedGaze() {
        double rx = rawGaze.getX(), ry = rawGaze.getY();
        double cx, cy;
        if (useQuadratic && quadParams != null) {
            cx = quadParams.applyX(rx);
            cy = quadParams.applyY(ry);
        } else {
            cx = calibrationParams.applyX(rx);
            cy = calibrationParams.applyY(ry);
        }
        // РАСШИРЕНО до ±1.5 — чтобы MouseController.edgeGain мог вытянуть до края экрана.
        return new Point2D(
                Math.max(-CALIB_CLAMP, Math.min(CALIB_CLAMP, cx)),
                Math.max(-CALIB_CLAMP, Math.min(CALIB_CLAMP, cy))
        );
    }

    public void setCalibrationParams(CalibrationParameters params) {
        this.calibrationParams = params;
        this.useQuadratic = false;
        this.quadParams   = null;
        logger.info("Linear calibration set: {}", params);
    }

    public void setQuadraticCalibrationParams(QuadraticCalibrationParameters params) {
        this.quadParams       = params;
        this.useQuadratic     = true;
        this.calibrationParams = new CalibrationParameters(1, 0, 1, 0);
        logger.info("Quadratic calibration set: {}", params);
    }

    public void resetCalibration() {
        this.calibrationParams = new CalibrationParameters(1, 0, 1, 0);
        this.quadParams        = null;
        this.useQuadratic      = false;
    }

    public boolean isCalibrated() {
        if (useQuadratic && quadParams != null)
            return !(Math.abs(quadParams.ax2) < 0.001 &&
                    Math.abs(quadParams.ax1 - 1.0) < 0.001 &&
                    Math.abs(quadParams.ax0) < 0.001);
        return calibrationParams != null &&
                !(Math.abs(calibrationParams.getAx() - 1.0) < 0.001 &&
                        Math.abs(calibrationParams.getBx())        < 0.001 &&
                        Math.abs(calibrationParams.getAy() - 1.0) < 0.001 &&
                        Math.abs(calibrationParams.getBy())        < 0.001);
    }

    public boolean isDetectorLoaded()   { return detectorLoaded; }
    public boolean isQuadraticCalibrated() { return useQuadratic && quadParams != null; }
    public GazeData getLastGazeData()   { return lastGazeData; }
    public int getBlinkCount()          { return blinkCount; }
    public void resetBlinkCount()       { blinkCount = 0; lastBlinkTime = System.currentTimeMillis(); }

    // ================================================================
    //  ВНУТРЕННИЕ КЛАССЫ КАЛИБРОВКИ — без изменений
    // ================================================================

    public static class CalibrationParameters {
        private final double ax, bx, ay, by;
        public CalibrationParameters(double ax, double bx, double ay, double by) {
            this.ax = ax; this.bx = bx; this.ay = ay; this.by = by;
        }
        public double applyX(double x) { return ax * x + bx; }
        public double applyY(double y) { return ay * y + by; }
        public double getAx() { return ax; } public double getBx() { return bx; }
        public double getAy() { return ay; } public double getBy() { return by; }
        @Override public String toString() {
            return String.format("X: %.3f*r+%.3f, Y: %.3f*r+%.3f", ax, bx, ay, by);
        }
    }

    public static class QuadraticCalibrationParameters {
        final double ax2, ax1, ax0, ay2, ay1, ay0;
        public QuadraticCalibrationParameters(double ax2, double ax1, double ax0,
                                              double ay2, double ay1, double ay0) {
            this.ax2=ax2; this.ax1=ax1; this.ax0=ax0;
            this.ay2=ay2; this.ay1=ay1; this.ay0=ay0;
        }
        public double applyX(double x) { return ax2*x*x + ax1*x + ax0; }
        public double applyY(double y) { return ay2*y*y + ay1*y + ay0; }
        @Override public String toString() {
            return String.format("X:%.3fx²+%.3fx+%.3f Y:%.3fy²+%.3fy+%.3f",
                    ax2,ax1,ax0,ay2,ay1,ay0);
        }
    }
}