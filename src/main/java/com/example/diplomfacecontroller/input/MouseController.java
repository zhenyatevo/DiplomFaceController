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
    /** Глобальный клик бровями — кликать по любому окну Windows */
    private volatile boolean globalClickEnabled = false;
    private long lastGlobalClickMs = 0;
    private static final long GLOBAL_CLICK_COOLDOWN_MS = 1500;
    private Dimension screenSize;

    private boolean enabled = false;

    // ===== СГЛАЖИВАНИЕ (КУРСОР В ПИКСЕЛЯХ) =====
    private double smoothX;
    private double smoothY;

    /**
     * БАЗОВЫЙ коэффициент сглаживания. Чем выше — тем плавнее, но медленнее.
     * 0.75 = агрессивное сглаживание (было 0.55/0.6) — курсор едва заметно
     * дёргается при фиксации взгляда.
     */
    private double smoothingFactor = 0.985;

    private double sensitivity = 1.0;

    /**
     * Мёртвая зона от центра (в нормированных координатах gaze).
     * Увеличена с 0.04 до 0.06 — меньше ложных движений у центра.
     */
    private double deadZoneRadius = 0.08;  // небольшая мёртвая зона

    /**
     * Усиление gaze для достижения краёв.
     * УМЕНЬШЕНО с 1.25 до 1.10 — после исправления GazeEstimator края и так
     * достигаются нормально, большое усиление только добавляло дрожь.
     */
    private double edgeGain = 1.0;   // убрано усиление краёв

    /**
     * Snap к краю: если цель ближе EDGE_SNAP_PX к краю — курсор прилипает.
     */
    private static final int EDGE_SNAP_PX = 0;

    /**
     * Минимальные отступы от края.
     */
    private static final int EDGE_MARGIN_PX = 0;

    /**
     * НОВОЕ: пиксельный deadband — если итоговая позиция отличается от текущей
     * курсорной меньше чем на столько пикселей, мышь не двигается.
     * Это убирает мелкое дёргание на уровне 1-2 пикселя.
     * Увеличено с 2-3 до 5 пикселей — заметно стабильнее при чтении.
     */
    private static final int PIXEL_DEADBAND = 8;   // уменьшен дедбенд — меньше залипания

    /**
     * НОВОЕ: ограничение максимальной скорости курсора.
     * Если цель внезапно дальше MAX_STEP_PX — делаем шаг на MAX_STEP_PX,
     * а не прыжок на весь экран. Защищает от резких рывков при выбросах gaze.
     */
    private static final int MAX_STEP_PX = 20;

    // Клики
    private long lastBlinkTime = 0;

    public MouseController() {
        try {
            robot = new Robot();
            screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            smoothX = screenSize.width / 2.0;
            smoothY = screenSize.height / 2.0;
            logger.info("MouseController initialized: {}x{}", screenSize.width, screenSize.height);
        } catch (AWTException e) {
            logger.error("Robot init failed", e);
        }
    }

    /**
     * Главный метод движения мыши.
     */
    public void updateMousePosition(Point2D calibratedGaze, GazeData gazeData) {
        if (!enabled || robot == null || calibratedGaze == null) return;

        // ===== ПАУЗА ПРИ МОРГАНИИ =====
        // Если глаза закрыты — курсор замер. GazeEstimator уже возвращает
        // последнее валидное значение, но дополнительная страховка.
        if (gazeData != null && gazeData.isLeftEyeClosed() && gazeData.isRightEyeClosed()) {
            return;
        }

        double gazeX = calibratedGaze.getX();
        double gazeY = calibratedGaze.getY();

        // Sanity-check
        if (Double.isNaN(gazeX) || Double.isNaN(gazeY) ||
                Math.abs(gazeX) > 3.0 || Math.abs(gazeY) > 3.0) {
            return;
        }

        // Усиление краёв
        gazeX *= edgeGain;
        gazeY *= edgeGain;

        gazeX = Math.max(-1.0, Math.min(1.0, gazeX));
        gazeY = Math.max(-1.0, Math.min(1.0, gazeY));

        // Мёртвая зона — по откалиброванным координатам (от центра экрана)
        if (isInDeadZone(gazeX, gazeY)) {
            return;
        }

        // Gaze → экран
        double targetX = (gazeX + 1) / 2.0 * screenSize.width;
        double targetY = (gazeY + 1) / 2.0 * screenSize.height;

        // Sensitivity вокруг центра
        double cx = screenSize.width  / 2.0;
        double cy = screenSize.height / 2.0;
        targetX = cx + (targetX - cx) * sensitivity;
        targetY = cy + (targetY - cy) * sensitivity;

        // Ограничение по краям
        targetX = Math.max(EDGE_MARGIN_PX, Math.min(screenSize.width  - 1 - EDGE_MARGIN_PX, targetX));
        targetY = Math.max(EDGE_MARGIN_PX, Math.min(screenSize.height - 1 - EDGE_MARGIN_PX, targetY));

        // EMA сглаживание
        smoothX = smoothX * smoothingFactor + targetX * (1 - smoothingFactor);
        smoothY = smoothY * smoothingFactor + targetY * (1 - smoothingFactor);

        // Snap к краю
        double finalX = smoothX;
        double finalY = smoothY;

        if (targetX <= EDGE_SNAP_PX) {
            finalX = EDGE_MARGIN_PX;
            smoothX = finalX;
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

        // ===== ОГРАНИЧЕНИЕ СКОРОСТИ =====
        // Предотвращаем резкие прыжки курсора при выбросах
        Point currentPos = MouseInfo.getPointerInfo().getLocation();
        double dx = finalX - currentPos.x;
        double dy = finalY - currentPos.y;
        double dist = Math.sqrt(dx * dx + dy * dy);

        if (dist > MAX_STEP_PX) {
            double scale = MAX_STEP_PX / dist;
            finalX = currentPos.x + dx * scale;
            finalY = currentPos.y + dy * scale;
            // Синхронизируем буфер, иначе следующий кадр опять дёрнется
            smoothX = finalX;
            smoothY = finalY;
        }

        // ===== ПИКСЕЛЬНЫЙ DEADBAND =====
        // Не двигаем мышь, если смещение меньше 5px — убирает мелкое дрожание
        if (Math.abs(finalX - currentPos.x) < PIXEL_DEADBAND &&
                Math.abs(finalY - currentPos.y) < PIXEL_DEADBAND) {
            return;
        }

        robot.mouseMove((int) Math.round(finalX), (int) Math.round(finalY));

        // Обработка морганий для кликов (только для двойных морганий — оставлено как было)
        if (gazeData != null) {
            handleBlinks(gazeData);
        }
    }

    private boolean isInDeadZone(double gazeX, double gazeY) {
        double distance = Math.sqrt(gazeX * gazeX + gazeY * gazeY);
        return distance < deadZoneRadius;
    }

    private void handleBlinks(GazeData gazeData) {
        long blinkTime = gazeData.getLastBlinkTime();
        if (blinkTime > lastBlinkTime + 50) {
            lastBlinkTime = blinkTime;
            performLeftClick();
            logger.debug("Blink click");
        }
    }

    public void performLeftClick() {
        if (robot == null) return;
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    public void setGlobalClickEnabled(boolean enabled) {
        this.globalClickEnabled = enabled;
        logger.info("Global brow-click: {}", enabled ? "ON" : "OFF");
    }

    public boolean isGlobalClickEnabled() { return globalClickEnabled; }

    public boolean wasRecentlyClicked() {
        return globalClickEnabled && (System.currentTimeMillis() - lastGlobalClickMs) < 300;
    }

    /**
     * Если глобальный клик включён и пришёл brow-триггер — делаем левый клик.
     * Вызывается из потока обработки данных (не JavaFX-поток).
     */
    public void processGlobalBrowClick(GazeData gazeData) {
        if (!globalClickEnabled || robot == null || gazeData == null) return;
        if (!gazeData.isBrowTriggerEvent()) return;
        long now = System.currentTimeMillis();
        if (now - lastGlobalClickMs < GLOBAL_CLICK_COOLDOWN_MS) return;
        lastGlobalClickMs = now;
        performLeftClick();
        logger.info("Global brow-click at cursor position");
    }

    public void performRightClick() {
        if (robot == null) return;
        robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
    }

    public void performDoubleClick() {
        performLeftClick();
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        performLeftClick();
    }

    public void handleHeadScroll(FaceData faceData) {
        if (!enabled || robot == null || faceData == null || !faceData.isFaceDetected()) return;
        double[] pose = faceData.getHeadPose();
        if (pose == null || pose.length < 1) return;
        double pitch = pose[0];
        if (pitch > 20) robot.mouseWheel(-1);
        else if (pitch < -20) robot.mouseWheel(1);
    }

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

    public boolean isEnabled() { return enabled; }

    public void setSensitivity(double sensitivity) {
        this.sensitivity = Math.max(0.5, Math.min(2.0, sensitivity));
    }

    public void setSmoothingFactor(double smoothingFactor) {
        this.smoothingFactor = Math.max(0.3, Math.min(0.95, smoothingFactor));
    }

    public void setDeadZoneRadius(double deadZoneRadius) {
        this.deadZoneRadius = Math.max(0.02, Math.min(0.20, deadZoneRadius));
    }

    public void setEdgeGain(double edgeGain) {
        this.edgeGain = Math.max(1.0, Math.min(2.0, edgeGain));
        logger.info("Edge gain set to {}", this.edgeGain);
    }

    public double getEdgeGain() { return edgeGain; }
    public double getSmoothingFactor() { return smoothingFactor; }
    public double getDeadZoneRadius() { return deadZoneRadius; }
}