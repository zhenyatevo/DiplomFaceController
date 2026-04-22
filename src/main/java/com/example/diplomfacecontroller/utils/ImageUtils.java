package com.example.diplomfacecontroller.utils;

import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayInputStream;

public class ImageUtils {

    /**
     * Конвертация OpenCV Mat в JavaFX Image
     */
    public static Image mat2Image(Mat mat) {
        if (mat == null || mat.empty()) {
            return null;
        }

        try {
            MatOfByte buffer = new MatOfByte();
            Imgcodecs.imencode(".png", mat, buffer);
            return new Image(new ByteArrayInputStream(buffer.toArray()));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Конвертация Mat в JavaFX Image (быстрый способ через пиксели)
     */
    public static Image matToJavaFxImage(Mat mat) {
        if (mat == null || mat.empty()) return null;

        int width = mat.width();
        int height = mat.height();
        int channels = mat.channels();

        // Конвертируем в BGRA если нужно
        Mat converted = new Mat();
        if (channels == 3) {
            Imgproc.cvtColor(mat, converted, Imgproc.COLOR_BGR2BGRA);
        } else if (channels == 1) {
            Imgproc.cvtColor(mat, converted, Imgproc.COLOR_GRAY2BGRA);
        } else {
            converted = mat.clone();
        }

        WritableImage writableImage = new WritableImage(width, height);
        PixelWriter pw = writableImage.getPixelWriter();

        byte[] pixels = new byte[width * height * 4];
        converted.get(0, 0, pixels);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = (y * width + x) * 4;
                int r = pixels[idx + 2] & 0xFF;
                int g = pixels[idx + 1] & 0xFF;
                int b = pixels[idx] & 0xFF;
                int a = pixels[idx + 3] & 0xFF;

                pw.setColor(x, y, Color.rgb(r, g, b, a / 255.0));
            }
        }

        return writableImage;
    }

    /**
     * Рисование прямоугольника на изображении
     */
    public static Mat drawRectangle(Mat mat, org.opencv.core.Rect rect, Scalar color, int thickness) {
        Imgproc.rectangle(mat, rect, color, thickness);
        return mat;
    }

    /**
     * Рисование точек (ключевых точек лица)
     */
    public static Mat drawPoints(Mat mat, MatOfPoint2f points, Scalar color) {
        if (points == null || points.empty()) return mat;

        Point[] pointsArray = points.toArray();
        for (Point p : pointsArray) {
            Imgproc.circle(mat, p, 2, color, -1);
        }
        return mat;
    }

    /**
     * Рисование текста на изображении
     */
    public static Mat drawText(Mat mat, String text, Point position, Scalar color) {
        Imgproc.putText(mat, text, position, Imgproc.FONT_HERSHEY_SIMPLEX,
                0.5, color, 1);
        return mat;
    }

    /**
     * Вырезание области лица
     */
    public static Mat extractFaceRegion(Mat frame, org.opencv.core.Rect faceRect) {
        return new Mat(frame, faceRect);
    }

    /**
     * Масштабирование изображения
     */
    public static Mat resize(Mat mat, int width, int height) {
        Mat resized = new Mat();
        Imgproc.resize(mat, resized, new Size(width, height));
        return resized;
    }
    /**
     * Конвертация в черно-белое
     */
    public static Mat toGrayscale(Mat mat) {
        Mat gray = new Mat();
        if (mat.channels() == 3) {
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY);
        } else if (mat.channels() == 4) {
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGRA2GRAY);
        } else {
            return mat.clone();
        }
        return gray;
    }
}