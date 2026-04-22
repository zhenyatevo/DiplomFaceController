package com.example.diplomfacecontroller.core;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MediaPipeBridge {
    private static final Logger logger = LoggerFactory.getLogger(MediaPipeBridge.class);

    private static final String HOST    = "localhost";
    private static final int    PORT    = 5678;
    private static final int    LM_COUNT = 478; // точек landmarks

    private Socket        socket;
    private DataOutputStream out;
    private DataInputStream  in;
    private Process       pythonProcess;

    private volatile boolean connected = false;

    public void start() throws Exception {
        // Запускаем mediapipe_server.py из корня проекта
        String projectDir = System.getProperty("user.dir");
        String scriptPath = projectDir + File.separator + "mediapipe_server.py";

        logger.info("Starting Python server: {}", scriptPath);

        ProcessBuilder pb = new ProcessBuilder("python", scriptPath);
        pb.directory(new File(projectDir));
        pb.redirectErrorStream(true);
        pythonProcess = pb.start();

        // Логируем вывод Python в отдельном потоке
        Thread logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(pythonProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("[Python] {}", line);
                }
            } catch (IOException ignored) {}
        }, "PythonLogThread");
        logThread.setDaemon(true);
        logThread.start();

        // Ждём пока Python-сервер стартует и начнёт слушать порт
        logger.info("Waiting for MediaPipe server to start...");
        Thread.sleep(4000);

        // Подключаемся к серверу
        connectWithRetry(5);
    }

    private void connectWithRetry(int maxAttempts) throws Exception {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                socket = new Socket(HOST, PORT);
                socket.setSoTimeout(2000); // таймаут чтения 2 сек
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                connected = true;
                logger.info("Connected to MediaPipe server on attempt {}", attempt);
                return;
            } catch (Exception e) {
                logger.warn("Connection attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) Thread.sleep(1500);
            }
        }
        throw new Exception("Cannot connect to MediaPipe server after " + maxAttempts + " attempts");
    }

    /**
     * Отправляет кадр Python-серверу и получает обратно landmarks.
     * Вызывается из потока захвата камеры (~30 fps).
     *
     * @param frame кадр с камеры (BGR Mat)
     * @return float[478*3] или null если лицо не найдено / ошибка
     */
    public float[] processFrame(Mat frame) {
        if (!connected || socket == null || socket.isClosed() || frame == null || frame.empty()) {
            return null;
        }

        try {
            // Кодируем Mat → JPEG (быстро, ~5-15 КБ при 320x240)
            MatOfByte buf = new MatOfByte();
            Imgcodecs.imencode(".jpg", frame, buf);
            byte[] jpegBytes = buf.toArray();

            // Шлём: 4 байта размера (big-endian) + JPEG
            out.writeInt(jpegBytes.length);
            out.write(jpegBytes);
            out.flush();

            // Читаем ответ: 4 байта размера payload
            int payloadSize = in.readInt();
            if (payloadSize == 0) {
                return null; // лицо не найдено
            }

            // Читаем float-массив (478 * 3 * 4 байта = 5736 байт)
            byte[] payload = new byte[payloadSize];
            in.readFully(payload);

            // Конвертируем bytes → float[]
            // numpy сохраняет в little-endian по умолчанию
            float[] landmarks = new float[LM_COUNT * 3];
            ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < landmarks.length; i++) {
                landmarks[i] = bb.getFloat();
            }
            return landmarks;

        } catch (java.net.SocketTimeoutException e) {
            // Python занят — не страшно, пропускаем кадр
            return null;
        } catch (Exception e) {
            logger.error("MediaPipe bridge error: {}", e.getMessage());
            connected = false;
            return null;
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public void stop() {
        connected = false;
        try {
            if (out != null) {
                out.writeInt(0); // сигнал завершения Python-серверу
                out.flush();
            }
        } catch (IOException ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        if (pythonProcess != null && pythonProcess.isAlive()) {
            pythonProcess.destroy();
            logger.info("Python process terminated");
        }
        logger.info("MediaPipe bridge stopped");
    }
}