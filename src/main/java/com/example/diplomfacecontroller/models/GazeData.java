package com.example.diplomfacecontroller.models;

import javafx.geometry.Point2D;

public class GazeData {
    private Point2D leftEyeGaze;
    private Point2D rightEyeGaze;
    private Point2D combinedGaze;
    private boolean leftEyeClosed;
    private boolean rightEyeClosed;
    private double blinkRate;
    private long lastBlinkTime;

    /** НОВОЕ: признак того что брови сейчас подняты (выше базового уровня). */
    private boolean browsRaised;

    /** НОВОЕ: триггер — событие "бровь поднялась" (rising edge).
     *  Устанавливается в true ровно на одном кадре, когда происходит переход
     *  "опущена → поднята". Используется для дискретных действий
     *  (нажатие клавиши на клавиатуре). */
    private boolean browTriggerEvent;
    private double browRatio;

    public GazeData() {
        this.leftEyeGaze = new Point2D(0, 0);
        this.rightEyeGaze = new Point2D(0, 0);
        this.combinedGaze = new Point2D(0, 0);
        this.leftEyeClosed = false;
        this.rightEyeClosed = false;
        this.blinkRate = 0;
        this.lastBlinkTime = 0;
        this.browsRaised = false;
        this.browTriggerEvent = false;
    }

    public Point2D getLeftEyeGaze() { return leftEyeGaze; }
    public void setLeftEyeGaze(Point2D leftEyeGaze) { this.leftEyeGaze = leftEyeGaze; }

    public Point2D getRightEyeGaze() { return rightEyeGaze; }
    public void setRightEyeGaze(Point2D rightEyeGaze) { this.rightEyeGaze = rightEyeGaze; }

    public Point2D getCombinedGaze() { return combinedGaze; }
    public void setCombinedGaze(Point2D combinedGaze) { this.combinedGaze = combinedGaze; }

    public boolean isLeftEyeClosed() { return leftEyeClosed; }
    public void setLeftEyeClosed(boolean leftEyeClosed) { this.leftEyeClosed = leftEyeClosed; }

    public boolean isRightEyeClosed() { return rightEyeClosed; }
    public void setRightEyeClosed(boolean rightEyeClosed) { this.rightEyeClosed = rightEyeClosed; }

    public double getBlinkRate() { return blinkRate; }
    public void setBlinkRate(double blinkRate) { this.blinkRate = blinkRate; }

    public long getLastBlinkTime() { return lastBlinkTime; }
    public void setLastBlinkTime(long lastBlinkTime) { this.lastBlinkTime = lastBlinkTime; }

    public boolean isBrowsRaised() { return browsRaised; }
    public void setBrowsRaised(boolean browsRaised) { this.browsRaised = browsRaised; }

    public boolean isBrowTriggerEvent() { return browTriggerEvent; }
    public void setBrowTriggerEvent(boolean browTriggerEvent) { this.browTriggerEvent = browTriggerEvent; }

    public boolean isBlinking() {
        return leftEyeClosed && rightEyeClosed;
    }

    public boolean isWinking() {
        return leftEyeClosed != rightEyeClosed;
    }

    public double getBrowRatio() {
        return browRatio;
    }

    public void setBrowRatio(double browRatio) {
        this.browRatio = browRatio;
    }
}