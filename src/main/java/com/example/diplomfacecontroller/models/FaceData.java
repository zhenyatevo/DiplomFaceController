package com.example.diplomfacecontroller.models;

import javafx.scene.shape.Rectangle;
import java.util.List;

public class FaceData {
    private Rectangle faceRect;
    private double[] headPose; // [pitch, yaw, roll]
    private List<FaceLandmark> landmarks;
    private boolean faceDetected;
    private Expression expression;

    // Enum для выражений лица
    public enum Expression {
        NEUTRAL,        // Нейтрально
        SMILE,          // Улыбка
        EYEBROWS_UP,    // Брови вверх
        EYES_CLOSED,    // Глаза закрыты
        MOUTH_OPEN      // Рот открыт
    }

    public FaceData() {
        this.faceDetected = false;
        this.expression = Expression.NEUTRAL;
    }

    // Геттеры и сеттеры
    public Rectangle getFaceRect() { return faceRect; }
    public void setFaceRect(Rectangle faceRect) { this.faceRect = faceRect; }

    public double[] getHeadPose() { return headPose; }
    public void setHeadPose(double[] headPose) { this.headPose = headPose; }

    public List<FaceLandmark> getLandmarks() { return landmarks; }
    public void setLandmarks(List<FaceLandmark> landmarks) { this.landmarks = landmarks; }

    public boolean isFaceDetected() { return faceDetected; }
    public void setFaceDetected(boolean faceDetected) { this.faceDetected = faceDetected; }

    public Expression getExpression() { return expression; }
    public void setExpression(Expression expression) { this.expression = expression; }

    // Внутренний класс для ключевых точек
    public static class FaceLandmark {
        private int id;
        private double x;
        private double y;

        public FaceLandmark(int id, double x, double y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }

        public int getId() { return id; }
        public double getX() { return x; }
        public double getY() { return y; }
    }
}