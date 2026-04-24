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

    private double smoothingFactor = 0.55;   // больше = плавнее, но медленнее
    private double sensitivity = 1.0;
    private double deadZoneRadius = 0.04;   // меньше мёртвая зона

    // ===== НОВЫЕ ПАРАМЕТРЫ =====
    /**
     * Коэффициент усиления входного gaze. Значение > 1.0 позволяет
     * достигать краёв экрана даже если откалиброванный gaze не доходит
     * до ±1. Например, 1.25 = активная зона экрана начинается с |gaze| = 0.8.
     */
    private double edgeGain = 1.25;

    /**
     * Расстояние от края экрана (в пикселях), в пределах которого
     * курсор "приклеивается" к краю, чтобы гарантированно достичь его
     * несмотря на EMA-сглаживание.
     */
    private static final int EDGE_SNAP_PX = 15;

    /**
     * Минимальные отступы от края экрана (0 = курсор может быть в самом углу).
     * На Windows иногда нужно 1px чтобы курсор не "проваливался" за экран.
     */
    private static final int EDGE_MARGIN_PX = 0;

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

        // Защита от совсем диких выбросов (gaze приходит уже с ограничением ±1.2 из GazeEstimator,
        // но оставим sanity-check на случай NaN/Infinity)
        if (Double.isNaN(gazeX) || Double.isNaN(gazeY) ||
                Math.abs(gazeX) > 3.0 || Math.abs(gazeY) > 3.0) {
            return;
        }

        // ===== УСИЛЕНИЕ ПО КРАЯМ =====
        // Умножаем gaze на edgeGain, чтобы края достигались раньше (при gaze ≈ ±0.8).
        // Затем жёстко ограничиваем в ±1, чтобы клампинг произошёл ПОСЛЕ усиления.
        gazeX *= edgeGain;
        gazeY *= edgeGain;

        gazeX = Math.max(-1.0, Math.min(1.0, gazeX));
        gazeY = Math.max(-1.0, Math.min(1.0, gazeY));

        // Мёртвая зона (считаем от центра, до усиления bigger не делала бы влияния —
        // делаем проверку уже на усиленном, но эффект небольшой)
        if (isInDeadZone(gazeX, gazeY)) {
            return;
        }

        // Перевод gaze → экран
        double targetX = (gazeX + 1) / 2.0 * screenSize.width;
        double targetY = (gazeY + 1) / 2.0 * screenSize.height;

        // Ограничение краёв (минимальное, чтобы курсор мог быть в самом углу)
        targetX = Math.max(EDGE_MARGIN_PX, Math.min(screenSize.width  - 1 - EDGE_MARGIN_PX, targetX));
        targetY = Math.max(EDGE_MARGIN_PX, Math.min(screenSize.height - 1 - EDGE_MARGIN_PX, targetY));

        // Чувствительность применяем до сглаживания — смещаем вокруг центра экрана
        double cx = screenSize.width  / 2.0;
        double cy = screenSize.height / 2.0;
        targetX = cx + (targetX - cx) * sensitivity;
        targetY = cy + (targetY - cy) * sensitivity;

        // Снова ограничиваем после sensitivity
        targetX = Math.max(EDGE_MARGIN_PX, Math.min(screenSize.width  - 1 - EDGE_MARGIN_PX, targetX));
        targetY = Math.max(EDGE_MARGIN_PX, Math.min(screenSize.height - 1 - EDGE_MARGIN_PX, targetY));

        // Сглаживание EMA
        smoothX = smoothX * smoothingFactor + targetX * (1 - smoothingFactor);
        smoothY = smoothY * smoothingFactor + targetY * (1 - smoothingFactor);

        // ===== SNAP К КРАЮ =====
        // EMA никогда не достигает край асимптотически. Если цель уже у края — пристёгиваем.
        double finalX = smoothX;
        double finalY = smoothY;

        if (targetX <= EDGE_SNAP_PX) {
            finalX = EDGE_MARGIN_PX;
            smoothX = finalX; // чтобы EMA не тянул обратно
        } else if (targetX >= screenSize.width - 1 - EDGE_SNAP_PX) {
            finalX = screenSize.width - 1 - EDGE_MARGIN_PX;
            smoothX = finalX;
        }

        if (targetY <= EDGE_SNAP_PX) {
            finalY = EDGE_MARGIN_PX;
            smoothY = finalY;
        } else if (targetY >= screenSize.height - 1 - EDGE_SNAP_PX) {
            finalY = screenSize.height - 1 - EDGE_MARGIN_PX;
            smoothY = finalY;
        }

        // Не двигаем мышь если смещение меньше 2 пикселей (снижаем дрожание)
        Point currentPos = MouseInfo.getPointerInfo().getLocation();
        if (Math.abs(finalX - currentPos.x) < 2 && Math.abs(finalY - currentPos.y) < 2) {
            return;
        }

        robot.mouseMove((int) Math.round(finalX), (int) Math.round(finalY));

        // Обработка морганий
        if (gazeData != null) {
            handleBlinks(gazeData);
        }
    }

    /**
     * Проверка мёртвой зоны (относительно gazeX, gazeY в диапазоне [-1, 1])
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

    /**
     * НОВОЕ: усиление gaze для достижения краёв.
     * 1.0 = без усиления (может не доходить до края)
     * 1.25 = по умолчанию (края достигаются при |gaze| ≈ 0.8)
     * 1.5  = агрессивно (края при |gaze| ≈ 0.67)
     */
    public void setEdgeGain(double edgeGain) {
        this.edgeGain = Math.max(1.0, Math.min(2.0, edgeGain));
        logger.info("Edge gain set to {}", this.edgeGain);
    }

    public double getEdgeGain() {
        return edgeGain;
    }
}