package com.example.diplomfacecontroller.data;

import javafx.geometry.Point2D;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class DatasetLoader {

    public static List<Point2D> load(String path) throws Exception {

        List<Point2D> data = new ArrayList<>();

        BufferedReader br = new BufferedReader(new FileReader(path));

        String line;

        br.readLine(); // header

        while ((line = br.readLine()) != null) {

            String[] parts = line.split(",");

            double x = Double.parseDouble(parts[3]);

            double y = Double.parseDouble(parts[4]);

            data.add(new Point2D(x, y));
        }

        br.close();

        return data;
    }
}
