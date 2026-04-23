package com.example.diplomfacecontroller.input;

import com.example.diplomfacecontroller.models.GazeData;
import com.example.diplomfacecontroller.models.FaceData;
import javafx.geometry.Point2D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.InputEvent;

public class MouseController {

    private static final Logger logger = LoggerFactory.getLogger(MouseController.class);

    private Robot robot;
    private Dimension screenSize;

    // Включение управления
    private boolean enabled = false;

    // Сглаживание
    private double smoothX;
    private double smoothY;

    private double smoothingFactor = 0.6;   // больше = плавнее, но медленнее
    private double sensitivity = 1.0;
    private double deadZoneRadius = 0.04;   // меньше мёртвая зона

    // Клики
    private long lastBlinkTime = 0;
    private static final long BLINK_CLICK_DELAY = 300;

    public MouseController() {

        try {

            robot = new Robot();
            screenSize = Toolkit.getDefaultToolkit().getScreenSize();

            // Начинаем из центра
            smoothX = screenSize.width / 2.0;
            smoothY = screenSize.height / 2.0;

            logger.info("MouseController initialized: {}x{}", screenSize.width, screenSize.height);

        } catch (AWTException e) {

            logger.error("Robot init failed", e);

        }
    }

    /**
     * Главный метод движения мыши
     */
    public void updateMousePosition(Point2D calibratedGaze, GazeData gazeData) {

        if (!enabled || robot == null || calibratedGaze == null) {

            return;
        }

        double gazeX = calibratedGaze.getX();
        double gazeY = calibratedGaze.getY();

        // Проверка диапазона
        if (Math.abs(gazeX) > 1.2 || Math.abs(gazeY) > 1.2) {

            return;
        }

        // Ограничиваем диапазон
        gazeX = Math.max(-1, Math.min(1, gazeX));
        gazeY = Math.max(-1, Math.min(1, gazeY));

        // Мёртвая зона
        if (isInDeadZone(gazeX, gazeY)) {
            return;
        }

        // Перевод gaze → экран
        double targetX = (gazeX + 1) / 2 * screenSize.width;

        double targetY = (gazeY + 1) / 2 * screenSize.height;

        // Ограничение краёв
        targetX = Math.max(5, Math.min(screenSize.width - 5, targetX));

        targetY = Math.max(5, Math.min(screenSize.height - 5, targetY));

        // Сглаживание EMA
        smoothX = smoothX * smoothingFactor + targetX * (1 - smoothingFactor);

        smoothY = smoothY * smoothingFactor + targetY * (1 - smoothingFactor);
        // ДОБАВИТЬ: не двигаем мышь если смещение меньше 3 пикселей
        Point currentPos = MouseInfo.getPointerInfo().getLocation();
        if (Math.abs(smoothX - currentPos.x) < 3 && Math.abs(smoothY - currentPos.y) < 3) {
            return;
        }

        // Чувствительность
        double finalX = smoothX * sensitivity;

        double finalY = smoothY * sensitivity;

        robot.mouseMove((int) finalX, (int) finalY);

        // Обработка морганий
        if (gazeData != null) {
            handleBlinks(gazeData);
        }
    }

    /**
     * Проверка мёртвой зоны
     */
    private boolean isInDeadZone(double gazeX, double gazeY) {

        double distance = Math.sqrt(gazeX * gazeX + gazeY * gazeY);

        return distance < deadZoneRadius;
    }

    /**
     * Клики по морганию
     */
    private void handleBlinks(GazeData gazeData) {

        long blinkTime = gazeData.getLastBlinkTime();

        if (blinkTime > lastBlinkTime + 50) {

            lastBlinkTime = blinkTime;

            performLeftClick();

            logger.debug("Blink click");
        }
    }

    /**
     * Левый клик
     */
    public void performLeftClick() {

        if (robot == null) return;

        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);

        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
        }

        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    /**
     * Правый клик
     */
    public void performRightClick() {

        if (robot == null) return;

        robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);

        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
        }

        robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
    }

    /**
     * Двойной клик
     */
    public void performDoubleClick() {

        performLeftClick();

        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }

        performLeftClick();
    }

    /**
     * Скролл по наклону головы
     */
    public void handleHeadScroll(FaceData faceData) {

        if (!enabled || robot == null || faceData == null || !faceData.isFaceDetected()) {

            return;
        }

        double[] pose = faceData.getHeadPose();

        if (pose == null || pose.length < 1) {

            return;
        }

        double pitch = pose[0];

        if (pitch > 20) {

            robot.mouseWheel(-1);

        } else if (pitch < -20) {

            robot.mouseWheel(1);

        }
    }

    /**
     * Центрирование
     */
    public void resetToCenter() {

        if (robot == null) return;

        int cx = screenSize.width / 2;

        int cy = screenSize.height / 2;

        robot.mouseMove(cx, cy);

        smoothX = cx;
        smoothY = cy;

        logger.info("Mouse centered");
    }

    // ===== Настройки =====

    public void setEnabled(boolean enabled) {

        this.enabled = enabled;

        logger.info("Mouse {}", enabled ? "enabled" : "disabled");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setSensitivity(double sensitivity) {

        this.sensitivity = Math.max(0.5, Math.min(2.0, sensitivity));
    }

    public void setSmoothingFactor(double smoothingFactor) {

        this.smoothingFactor = Math.max(0.2, Math.min(0.8, smoothingFactor));
    }

    public void setDeadZoneRadius(double deadZoneRadius) {

        this.deadZoneRadius = Math.max(0.02, Math.min(0.15, deadZoneRadius));
    }
}