package com.example.diplomfacecontroller.data;

import javafx.geometry.Point2D;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DatasetBatchLoader {

    public static List<Point2D> loadAllDatasets(String folderPath) throws Exception {

        List<Point2D> allData = new ArrayList<>();

        File folder = new File(folderPath);

        File[] files = folder.listFiles((dir, name) -> name.endsWith(".csv"));

        if (files == null) {
            throw new Exception("No CSV files found");
        }

        for (File file : files) {

            System.out.println("Loading: " + file.getName());

            List<Point2D> data = DatasetLoader.load(file.getAbsolutePath());

            allData.addAll(data);
        }

        System.out.println("Total points loaded: " + allData.size());

        return allData;
    }
}