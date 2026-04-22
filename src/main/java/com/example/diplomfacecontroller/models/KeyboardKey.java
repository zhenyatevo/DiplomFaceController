package com.example.diplomfacecontroller.models;

import javafx.geometry.Rectangle2D;

public class KeyboardKey {
    private String primaryChar;
    private String shiftChar;
    private Rectangle2D bounds;
    private boolean isPressed = false;
    private long lastHoverTime = 0;
    private double hoverProgress = 0;

    // Специальные клавиши
    private boolean isSpecial = false;
    private String action; // "BACKSPACE", "ENTER", "SPACE", "SHIFT", "CLEAR"

    public KeyboardKey(String primaryChar, String shiftChar, Rectangle2D bounds) {
        this.primaryChar = primaryChar;
        this.shiftChar = shiftChar;
        this.bounds = bounds;
        this.isSpecial = false;
    }

    public KeyboardKey(String action, Rectangle2D bounds) {
        this.action = action;
        this.bounds = bounds;
        this.isSpecial = true;
        this.primaryChar = action;
        this.shiftChar = action;
    }

    // Геттеры и сеттеры
    public String getPrimaryChar() { return primaryChar; }
    public String getShiftChar() { return shiftChar; }
    public Rectangle2D getBounds() { return bounds; }
    public boolean isPressed() { return isPressed; }
    public void setPressed(boolean pressed) { isPressed = pressed; }
    public long getLastHoverTime() { return lastHoverTime; }
    public void setLastHoverTime(long time) { lastHoverTime = time; }
    public double getHoverProgress() { return hoverProgress; }
    public void setHoverProgress(double progress) { hoverProgress = progress; }
    public boolean isSpecial() { return isSpecial; }
    public String getAction() { return action; }

    public boolean containsPoint(double x, double y) {
        return bounds.contains(x, y);
    }

    public String getChar(boolean shiftPressed) {
        if (isSpecial) return "";
        return shiftPressed ? shiftChar : primaryChar;
    }
}