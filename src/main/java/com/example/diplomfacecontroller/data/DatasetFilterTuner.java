package com.example.diplomfacecontroller.data;

import javafx.geometry.Point2D;

import java.util.List;

public class DatasetFilterTuner {

    public static void runTest(String datasetPath) throws Exception {

        List<Point2D> rawData = DatasetLoader.load(datasetPath);

        double[] alphas = {0.1, 0.2, 0.3, 0.4, 0.5};

        for (double alpha : alphas) {

            EMAFilter filter = new EMAFilter(alpha);

            double totalError = 0;

            Point2D prev = null;

            for (Point2D p : rawData) {

                Point2D smooth = filter.apply(p);

                if (prev != null) {

                    double dx = smooth.getX() - prev.getX();

                    double dy = smooth.getY() - prev.getY();

                    double jitter = Math.sqrt(dx * dx + dy * dy);

                    totalError += jitter;
                }

                prev = smooth;
            }

            double avgError = totalError / rawData.size();

            System.out.println("Alpha " + alpha + " jitter = " + avgError);
        }
    }
}
