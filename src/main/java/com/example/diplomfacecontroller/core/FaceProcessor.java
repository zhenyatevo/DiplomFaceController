package com.example.diplomfacecontroller.core;

import com.example.diplomfacecontroller.models.FaceData;
import com.example.diplomfacecontroller.utils.ImageUtils;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class FaceProcessor {
    private static final Logger logger = LoggerFactory.getLogger(FaceProcessor.class);

    private CascadeClassifier faceDetector;
    private CascadeClassifier eyeDetector;
    private boolean detectorsLoaded = false;

    // 3D модель лица для определения поворота
    private MatOfPoint3f modelPoints;

    // Параметры камеры (приблизительные)
    private Mat cameraMatrix;
    private Mat distCoeffs;

    // НОВОЕ ПОЛЕ: для хранения последних обработанных данных лица
    private FaceData lastFaceData = new FaceData();

    public FaceProcessor() {
        initDetectors();
        init3DModel();
        initCameraCalibration();
    }

    private void initDetectors() {
        try {
            // ✅ Убедитесь, что OpenCV загружен ДО создания CascadeClassifier
            org.bytedeco.javacpp.Loader.load(org.bytedeco.opencv.opencv_java.class);

            faceDetector = new CascadeClassifier();
            eyeDetector = new CascadeClassifier();

            // Загружаем каскады из ресурсов с помощью временных файлов
            boolean faceLoaded = loadCascade(faceDetector, "/cascades/haarcascade_frontalface_alt.xml", "face");
            boolean eyeLoaded = loadCascade(eyeDetector, "/cascades/haarcascade_eye.xml", "eye");

            if (faceLoaded && eyeLoaded) {
                detectorsLoaded = true;
                logger.info("Detectors loaded successfully");
            } else {
                logger.error("Failed to load one or both cascade files");
            }
        } catch (Exception e) {
            logger.error("Failed to load detectors: " + e.getMessage(), e);
        }
    }

    /**
     * Загрузка каскада из ресурсов с созданием временного файла
     */
    private boolean loadCascade(CascadeClassifier detector, String resourcePath, String name) {
        try {
            // Получаем InputStream из ресурсов
            InputStream inputStream = getClass().getResourceAsStream(resourcePath);

            if (inputStream == null) {
                logger.error("{} cascade file not found in resources: {}", name, resourcePath);
                return false;
            }

            // Создаем временный файл
            File tempFile = File.createTempFile("opencv_" + name + "_", ".xml");
            tempFile.deleteOnExit();

            // Копируем содержимое во временный файл
            Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            // Загружаем каскад из временного файла
            boolean loaded = detector.load(tempFile.getAbsolutePath());

            if (loaded) {
                logger.debug("{} cascade loaded successfully from temp file", name);
            } else {
                logger.error("Failed to load {} cascade from temp file", name);
            }

            return loaded;

        } catch (Exception e) {
            logger.error("Error loading {} cascade: {}", name, e.getMessage());
            return false;
        }
    }

    private void init3DModel() {
        // Упрощенная 3D модель лица (координаты в мм)
        Point3[] points = {
                new Point3(0.0, 0.0, 0.0),        // Нос
                new Point3(-30.0, -30.0, -30.0),  // Левый глаз
                new Point3(30.0, -30.0, -30.0),   // Правый глаз
                new Point3(-25.0, 30.0, -30.0),   // Левый угол рта
                new Point3(25.0, 30.0, -30.0)     // Правый угол рта
        };

        modelPoints = new MatOfPoint3f(points);
    }

    private void initCameraCalibration() {
        // Приблизительная калибровка камеры
        cameraMatrix = new Mat(3, 3, CvType.CV_64F);
        cameraMatrix.put(0, 0, 640.0);  // fx
        cameraMatrix.put(0, 1, 0);
        cameraMatrix.put(0, 2, 320.0);  // cx
        cameraMatrix.put(1, 0, 0);
        cameraMatrix.put(1, 1, 640.0);  // fy
        cameraMatrix.put(1, 2, 240.0);  // cy
        cameraMatrix.put(2, 0, 0);
        cameraMatrix.put(2, 1, 0);
        cameraMatrix.put(2, 2, 1);

        distCoeffs = new Mat(1, 4, CvType.CV_64F);
        distCoeffs.put(0, 0, 0, 0, 0, 0);
    }

    /**
     * Основной метод обработки кадра
     */
    public FaceData processFrame(Mat frame) {
        FaceData result = new FaceData();

        if (frame == null || frame.empty()) {
            // Сохраняем пустой результат
            this.lastFaceData = result;
            return result;
        }

        // Если детекторы не загружены, возвращаем пустой результат
        if (!detectorsLoaded) {
            // Сохраняем пустой результат
            this.lastFaceData = result;
            return result;
        }

        // Конвертация в grayscale для детекции
        Mat gray = ImageUtils.toGrayscale(frame);

        // 1. Поиск лица
        MatOfRect faces = new MatOfRect();
        faceDetector.detectMultiScale(gray, faces);

        Rect[] facesArray = faces.toArray();
        if (facesArray.length == 0) {
            // Сохраняем результат (с faceDetected = false)
            this.lastFaceData = result;
            return result;
        }

        // Берем первое лицо
        Rect faceRect = facesArray[0];
        result.setFaceDetected(true);
        result.setFaceRect(new javafx.scene.shape.Rectangle(
                faceRect.x, faceRect.y, faceRect.width, faceRect.height
        ));

        // Рисуем прямоугольник вокруг лица
        Imgproc.rectangle(frame, faceRect, new Scalar(0, 255, 0), 2);

        // 2. Поиск глаз внутри лица
        Mat faceROI = gray.submat(faceRect);
        MatOfRect eyes = new MatOfRect();
        eyeDetector.detectMultiScale(faceROI, eyes);

        Rect[] eyesArray = eyes.toArray();
        for (Rect eye : eyesArray) {
            // Смещаем координаты относительно всего кадра
            Rect eyeRect = new Rect(
                    faceRect.x + eye.x,
                    faceRect.y + eye.y,
                    eye.width,
                    eye.height
            );
            Imgproc.rectangle(frame, eyeRect, new Scalar(255, 0, 0), 2);
        }

        // 3. Определение выражения
        result.setExpression(detectExpression(faceROI, eyesArray.length));

        // 4. Вычисление поворота головы (упрощенно для UI)
        double[] headPose = estimateHeadPose(faceRect, frame.width(), frame.height());
        result.setHeadPose(headPose);

        // Добавляем текст с информацией
        String poseText = String.format("Face: yaw=%.1f", headPose[1]);
        Imgproc.putText(frame, poseText,
                new Point(faceRect.x, faceRect.y - 10),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, new Scalar(0, 255, 0), 1);

        // СОХРАНЯЕМ результат в lastFaceData
        this.lastFaceData = result;

        return result;
    }

    /**
     * НОВЫЙ МЕТОД: Упрощенное вычисление поворота головы для UI
     */
    private double[] estimateHeadPose(Rect faceRect, int frameWidth, int frameHeight) {
        double[] headPose = new double[3]; // pitch, yaw, roll

        if (faceRect == null) {
            return headPose;
        }

        // Вычисляем центр лица
        double faceCenterX = faceRect.x + faceRect.width / 2.0;
        double frameCenter = frameWidth / 2.0;

        // yaw (поворот влево-вправо): отрицательный - влево, положительный - вправо
        // Максимальный поворот примерно 30 градусов при смещении к краю
        headPose[1] = (faceCenterX - frameCenter) / frameCenter * 30;

        // pitch (наклон вперед-назад) - можно оценить по соотношению высоты лица
        // roll (наклон вбок) - для простоты оставляем 0

        return headPose;
    }

    /**
     * Упрощенное определение выражения по области лица
     */
    private FaceData.Expression detectExpression(Mat faceROI, int eyeCount) {
        // Если глаз меньше 2 - возможно моргание
        if (eyeCount < 2) {
            return FaceData.Expression.EYES_CLOSED;
        }

        // TODO: Добавить более сложную логику для улыбки и бровей
        // Пока возвращаем NEUTRAL
        return FaceData.Expression.NEUTRAL;
    }

    /**
     * НОВЫЙ МЕТОД: Получение последних обработанных данных лица
     * @return последний объект FaceData, полученный в processFrame
     */
    public FaceData getLastFaceData() {
        return lastFaceData;
    }

    /**
     * Проверка, загружены ли детекторы
     */
    public boolean isDetectorsLoaded() {
        return detectorsLoaded;
    }

    /**
     * Получение координат для 2D точек лица (упрощенно)
     */
    private MatOfPoint2f getImagePoints(Rect faceRect) {
        double centerX = faceRect.x + faceRect.width / 2.0;
        double centerY = faceRect.y + faceRect.height / 2.0;

        Point[] points = {
                new Point(centerX, centerY), // нос
                new Point(faceRect.x + faceRect.width * 0.3, faceRect.y + faceRect.height * 0.4), // левый глаз
                new Point(faceRect.x + faceRect.width * 0.7, faceRect.y + faceRect.height * 0.4), // правый глаз
                new Point(faceRect.x + faceRect.width * 0.3, faceRect.y + faceRect.height * 0.7), // левый угол рта
                new Point(faceRect.x + faceRect.width * 0.7, faceRect.y + faceRect.height * 0.7)  // правый угол рта
        };

        return new MatOfPoint2f(points);
    }
}