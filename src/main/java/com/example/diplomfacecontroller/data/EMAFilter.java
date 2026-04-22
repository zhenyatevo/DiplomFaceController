package com.example.diplomfacecontroller.data;

import javafx.geometry.Point2D;

public class EMAFilter {

    private double alpha;

    private double prevX = 0;
    private double prevY = 0;

    public EMAFilter(double alpha) {
        this.alpha = alpha;
    }

    public Point2D apply(Point2D raw) {

        double smoothX = alpha * raw.getX() + (1 - alpha) * prevX;

        double smoothY = alpha * raw.getY() + (1 - alpha) * prevY;

        prevX = smoothX;
        prevY = smoothY;

        return new Point2D(smoothX, smoothY);
    }
}
