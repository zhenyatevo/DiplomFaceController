package com.example.diplomfacecontroller.core;

import com.example.diplomfacecontroller.models.GazeData; // Добавляем импорт
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.diplomfacecontroller.utils.ImageUtils;
import javafx.scene.image.ImageView;
import javafx.scene.image.Image;

import java.util.concurrent.atomic.AtomicBoolean;

public class CameraManager {
    private static final Logger logger = LoggerFactory.getLogger(CameraManager.class);

    private VideoCapture faceCamera;
    private VideoCapture eyeCamera;
    private Thread faceCaptureThread;
    private Thread eyeCaptureThread;
    private Thread displayThread;
    private volatile boolean running = false;
    private final AtomicBoolean faceCameraReady = new AtomicBoolean(false);
    private final AtomicBoolean eyeCameraReady = new AtomicBoolean(false);

    private final ImageView faceView;
    private final ImageView eyeView;

    private Image latestFaceImage;
    private Image latestEyeImage;
    private final Object faceLock = new Object();
    private final Object eyeLock = new Object();

    private volatile boolean faceImageUpdated = false;
    private volatile boolean eyeImageUpdated = false;

    private long lastFpsLog = System.currentTimeMillis();
    private int faceCaptureFrames = 0;
    private int eyeCaptureFrames = 0;
    private int displayFrames = 0;

    // Ссылка на GazeEstimator для анализа кадров глаз
    private GazeEstimator gazeEstimator;

    // ОДИНАКОВЫЙ размер для обоих окон - 320x240
    private static final int DISPLAY_WIDTH = 320;
    private static final int DISPLAY_HEIGHT = 240;

    private static final int DISPLAY_INTERVAL_MS = 33;

    public CameraManager(ImageView faceView, ImageView eyeView) {
        this.faceView = faceView;
        this.eyeView = eyeView;

        // Устанавливаем одинаковый размер для обоих ImageView
        faceView.setFitWidth(DISPLAY_WIDTH);
        faceView.setFitHeight(DISPLAY_HEIGHT);
        faceView.setPreserveRatio(true);

        eyeView.setFitWidth(DISPLAY_WIDTH);
        eyeView.setFitHeight(DISPLAY_HEIGHT);
        eyeView.setPreserveRatio(true);
    }

    // Добавляем метод для установки GazeEstimator
    public void setGazeEstimator(GazeEstimator gazeEstimator) {
        this.gazeEstimator = gazeEstimator;
        logger.info("GazeEstimator set in CameraManager");
    }

    public void startCameras(int faceCamId, int eyeCamId) {
        if (running) {
            logger.warn("Cameras already running");
            return;
        }

        logger.info("========== STARTING CAMERAS ==========");
        logger.info("Face camera ID: {}, Eye camera ID: {}", faceCamId, eyeCamId);
        running = true;

        // Открываем камеры последовательно
        openFaceCamera(faceCamId);

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        openEyeCamera(eyeCamId);

        startCaptureThreads();
        startDisplayThread();

        logger.info("Camera initialization complete");
    }

    private void openFaceCamera(int camId) {
        logger.info("Opening face camera {}...", camId);

        for (int attempt = 1; attempt <= 5; attempt++) {
            VideoCapture cam = new VideoCapture();

            try {
                logger.info("Face camera attempt {}/5", attempt);

                if (cam.open(camId, Videoio.CAP_DSHOW)) {
                    Thread.sleep(1500);

                    if (cam.isOpened()) {
                        double width = cam.get(Videoio.CAP_PROP_FRAME_WIDTH);
                        double height = cam.get(Videoio.CAP_PROP_FRAME_HEIGHT);
                        logger.info("Face camera native resolution: {}x{}", (int)width, (int)height);

                        // Устанавливаем небольшое разрешение для скорости
                        cam.set(Videoio.CAP_PROP_FRAME_WIDTH, DISPLAY_WIDTH);
                        cam.set(Videoio.CAP_PROP_FRAME_HEIGHT, DISPLAY_HEIGHT);
                        cam.set(Videoio.CAP_PROP_FPS, 30);
                        cam.set(Videoio.CAP_PROP_BUFFERSIZE, 1);

                        // Прогрев камеры
                        Mat warmupFrame = new Mat();
                        for (int i = 0; i < 3; i++) {
                            cam.read(warmupFrame);
                            Thread.sleep(100);
                        }
                        warmupFrame.release();

                        boolean working = false;
                        for (int test = 1; test <= 3; test++) {
                            Mat testFrame = new Mat();
                            if (cam.read(testFrame) && !testFrame.empty()) {
                                logger.info("✅ Face camera test {}/3 passed: {}x{}",
                                        test, testFrame.width(), testFrame.height());
                                testFrame.release();
                                working = true;
                                break;
                            }
                            testFrame.release();
                            Thread.sleep(300);
                        }

                        if (working) {
                            faceCamera = cam;
                            faceCameraReady.set(true);
                            logger.info("✅ Face camera ready");
                            return;
                        }
                    }
                }

                cam.release();
                logger.warn("Face camera attempt {} failed", attempt);
                Thread.sleep(2000);

            } catch (Exception e) {
                logger.error("Face camera error: {}", e.getMessage());
                cam.release();
                try { Thread.sleep(2000); } catch (InterruptedException ie) {}
            }
        }

        logger.error("❌ Failed to open face camera");
    }

    private void openEyeCamera(int camId) {
        logger.info("Opening eye camera {}...", camId);

        for (int attempt = 1; attempt <= 5; attempt++) {
            VideoCapture cam = new VideoCapture();

            try {
                logger.info("Eye camera attempt {}/5", attempt);

                if (cam.open(camId, Videoio.CAP_DSHOW)) {
                    Thread.sleep(1500);

                    if (cam.isOpened()) {
                        double width = cam.get(Videoio.CAP_PROP_FRAME_WIDTH);
                        double height = cam.get(Videoio.CAP_PROP_FRAME_HEIGHT);
                        logger.info("Eye camera native resolution: {}x{}", (int)width, (int)height);

                        // Устанавливаем ТОЧНО ТАКОЕ ЖЕ разрешение как для лица
                        cam.set(Videoio.CAP_PROP_FRAME_WIDTH, DISPLAY_WIDTH);
                        cam.set(Videoio.CAP_PROP_FRAME_HEIGHT, DISPLAY_HEIGHT);
                        cam.set(Videoio.CAP_PROP_FPS, 30);
                        cam.set(Videoio.CAP_PROP_BUFFERSIZE, 1);

                        // Прогрев камеры - особенно важно для глаз!
                        logger.info("Warming up eye camera...");
                        Mat warmupFrame = new Mat();
                        for (int i = 0; i < 5; i++) {
                            if (cam.read(warmupFrame) && !warmupFrame.empty()) {
                                logger.info("Eye camera warmup frame {}/5 captured", i + 1);
                            }
                            Thread.sleep(200);
                        }
                        warmupFrame.release();

                        // Финальная проверка
                        Mat testFrame = new Mat();
                        if (cam.read(testFrame) && !testFrame.empty()) {
                            logger.info("✅ Eye camera working: {}x{}", testFrame.width(), testFrame.height());
                            testFrame.release();

                            eyeCamera = cam;
                            eyeCameraReady.set(true);
                            logger.info("✅ Eye camera ready");
                            return;
                        }
                        testFrame.release();
                    }
                }

                cam.release();
                logger.warn("Eye camera attempt {} failed", attempt);
                Thread.sleep(2000);

            } catch (Exception e) {
                logger.error("Eye camera error: {}", e.getMessage());
                cam.release();
                try { Thread.sleep(2000); } catch (InterruptedException ie) {}
            }
        }

        logger.error("❌ Failed to open eye camera");
    }

    private void startCaptureThreads() {
        if (faceCameraReady.get()) {
            faceCaptureThread = new Thread(() -> {
                logger.info("Face capture started");
                Mat frame = new Mat();

                while (running && faceCameraReady.get()) {
                    try {
                        if (faceCamera != null && faceCamera.read(frame) && !frame.empty()) {
                            // Кадр уже нужного размера, просто конвертируем
                            final Image image = ImageUtils.mat2Image(frame);

                            synchronized (faceLock) {
                                latestFaceImage = image;
                                faceImageUpdated = true;
                            }
                            faceCaptureFrames++;
                        }
                        Thread.sleep(10);
                    } catch (Exception e) {
                        logger.error("Face capture error: {}", e.getMessage());
                    }
                }
                logger.info("Face capture stopped");
            });
            faceCaptureThread.start();
        }

        if (eyeCameraReady.get()) {
            eyeCaptureThread = new Thread(() -> {
                logger.info("Eye capture started");
                Mat frame = new Mat();

                while (running && eyeCameraReady.get()) {
                    try {
                        if (eyeCamera != null && eyeCamera.read(frame) && !frame.empty()) {

                            // ========== ДОБАВЛЕННАЯ ОТЛАДКА ==========
                            // Проверяем, получен ли кадр от камеры глаз
                            if (eyeCaptureFrames % 30 == 0) {
                                logger.info("Eye camera frame received: {}x{}", frame.width(), frame.height());
                            }

                            // Передаем кадр в GazeEstimator для анализа, если он установлен
                            if (gazeEstimator != null) {
                                GazeData gazeData = gazeEstimator.analyzeGaze(frame);
                                if (eyeCaptureFrames % 30 == 0 && gazeData != null) {
                                    logger.info("Gaze data after analysis: combined={}", gazeData.getCombinedGaze());
                                }
                            } else {
                                if (eyeCaptureFrames % 30 == 0) {
                                    logger.warn("GazeEstimator not set in CameraManager");
                                }
                            }
                            // ========== КОНЕЦ ДОБАВЛЕННОЙ ОТЛАДКИ ==========

                            // Конвертируем для отображения
                            final Image image = ImageUtils.mat2Image(frame);

                            synchronized (eyeLock) {
                                latestEyeImage = image;
                                eyeImageUpdated = true;
                            }
                            eyeCaptureFrames++;

                        } else {
                            if (eyeCaptureFrames % 30 == 0) {
                                logger.warn("Eye camera read failed or empty frame");
                            }
                            Thread.sleep(10);
                        }
                        Thread.sleep(10);
                    } catch (Exception e) {
                        logger.error("Eye capture error: {}", e.getMessage());
                    }
                }
                logger.info("Eye capture stopped");
            });
            eyeCaptureThread.start();
        }
    }

    private void startDisplayThread() {
        displayThread = new Thread(() -> {
            logger.info("Display thread started");

            while (running) {
                try {
                    boolean updated = false;

                    if (faceImageUpdated) {
                        Image img;
                        synchronized (faceLock) {
                            img = latestFaceImage;
                            faceImageUpdated = false;
                        }
                        if (img != null) {
                            final Image displayImg = img;
                            javafx.application.Platform.runLater(() -> faceView.setImage(displayImg));
                            updated = true;
                        }
                    }

                    if (eyeImageUpdated) {
                        Image img;
                        synchronized (eyeLock) {
                            img = latestEyeImage;
                            eyeImageUpdated = false;
                        }
                        if (img != null) {
                            final Image displayImg = img;
                            javafx.application.Platform.runLater(() -> eyeView.setImage(displayImg));
                            updated = true;
                        }
                    }

                    if (updated) {
                        displayFrames++;
                    }

                    long now = System.currentTimeMillis();
                    if (now - lastFpsLog > 5000) {
                        logger.info("Face FPS: {}, Eye FPS: {}, Display: {}",
                                faceCaptureFrames / 5, eyeCaptureFrames / 5, displayFrames / 5);
                        faceCaptureFrames = 0;
                        eyeCaptureFrames = 0;
                        displayFrames = 0;
                        lastFpsLog = now;
                    }

                    Thread.sleep(DISPLAY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        displayThread.start();
    }

    public void stopCameras() {
        logger.info("Stopping cameras");
        running = false;

        try {
            if (faceCaptureThread != null) {
                faceCaptureThread.join(2000);
            }
            if (eyeCaptureThread != null) {
                eyeCaptureThread.join(2000);
            }
            if (displayThread != null) {
                displayThread.interrupt();
                displayThread.join(2000);
            }
        } catch (InterruptedException e) {
            logger.error("Error stopping threads", e);
        }

        if (faceCamera != null) {
            faceCamera.release();
            faceCamera = null;
        }
        if (eyeCamera != null) {
            eyeCamera.release();
            eyeCamera = null;
        }

        faceCameraReady.set(false);
        eyeCameraReady.set(false);

        logger.info("Cameras stopped");
    }

    public boolean isRunning() { return running; }
    public boolean isFaceCameraReady() { return faceCameraReady.get(); }
    public boolean isEyeCameraReady() { return eyeCameraReady.get(); }
}