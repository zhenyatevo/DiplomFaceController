package com.example.diplomfacecontroller.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Статистика сессии. Пишет ОДИН CSV-файл в папку {@code stats/}:
 * {@code session_YYYY_MM_DD_HH_mm_ss.csv}.
 *
 * <p>Колонка {@code kind} различает строки двух типов:
 * <ul>
 *   <li>{@code frame} — метрики кадра трекинга: FPS, latency, brow_ratio,
 *       gaze x/y, face_detected. Сэмплируется (каждый N-й кадр, по умолчанию
 *       каждый 3-й ≈ 10 fps записи). Кадр с {@code brow_trigger=true} пишется
 *       всегда.</li>
 *   <li>{@code event} — дискретные действия с тегом {@code event_type} и
 *       строкой {@code detail}: "brow_trigger", "key_inject_ok",
 *       "key_inject_failed", "calib_done", "mouse_click", "session_start",
 *       "session_stop".</li>
 * </ul>
 *
 * <p>Анализ в pandas:
 * <pre>{@code
 *   import pandas as pd
 *   df = pd.read_csv('stats/session_...csv')
 *   frames = df[df.kind == 'frame']
 *   events = df[df.kind == 'event']
 *   # пример: распределение brow-trigger по зонам
 *   events[events.event_type == 'brow_trigger'].detail.value_counts()
 *   # success rate inject (когда добавим в анализ)
 *   ok = (events.event_type == 'key_inject_ok').sum()
 *   fail = (events.event_type == 'key_inject_failed').sum()
 * }</pre>
 *
 * <p>Все методы thread-safe.
 */
public class StatsLoggerUtils {

    private static final Logger logger = LoggerFactory.getLogger(StatsLoggerUtils.class);

    private static final DateTimeFormatter FILE_FMT =
            DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss");

    private static final String HEADER =
            "ts_ms_rel,ts_ms_abs,kind,fps,frame_ms,face_detected," +
                    "brow_ratio,brow_trigger,gaze_x,gaze_y,event_type,detail";

    private static volatile BufferedWriter writer;
    private static final Object LOCK = new Object();

    private static volatile int frameSampleEvery = 3;
    private static final AtomicLong frameCounter = new AtomicLong(0);
    private static volatile long sessionStartMs = 0;

    public static void startSession() {
        stopSession(); // на случай повторного вызова без stop
        sessionStartMs = System.currentTimeMillis();
        String stamp = LocalDateTime.now().format(FILE_FMT);

        try {
            File dir = new File("stats");
            if (!dir.exists() && !dir.mkdirs()) {
                logger.warn("Cannot create stats dir: {}", dir.getAbsolutePath());
            }
            File file = new File(dir, "session_" + stamp + ".csv");

            // UTF-8: иначе detail с кириллицей (имя клавиши "ё") пишется
            // кракозябрами, и pandas/Excel показывают их некорректно.
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8));
            writer.write(HEADER);
            writer.newLine();
            writer.flush();

            logger.info("Stats session started: {}", file.getName());
            event("session_start", "");
        } catch (IOException e) {
            logger.error("Cannot start stats session", e);
            writer = null;
        }
    }

    public static void log(
            double fps,
            double frameMs,
            boolean faceDetected,
            double browRatio,
            boolean browTrigger,
            double gazeX,
            double gazeY
    ) {
        if (writer == null) return;

        long n = frameCounter.incrementAndGet();
        // Кадр с триггером бровей пишем всегда, чтобы потом точно знать
        // timestamp каждого срабатывания и сопоставить с key_inject_ok.
        if (!browTrigger && (n % Math.max(1, frameSampleEvery) != 0)) {
            return;
        }

        long abs = System.currentTimeMillis();
        long rel = abs - sessionStartMs;

        // 12 колонок. event_type и detail остаются пустыми для frame.
        String row = rel + "," + abs + ",frame," +
                fmt(fps) + "," +
                fmt(frameMs) + "," +
                faceDetected + "," +
                fmt(browRatio) + "," +
                browTrigger + "," +
                fmt(gazeX) + "," +
                fmt(gazeY) + "," +
                ",";

        synchronized (LOCK) {
            try {
                writer.write(row);
                writer.newLine();
            } catch (IOException e) {
                logger.error("Frame write error", e);
            }
        }
    }

    public static void event(String eventType, String detail) {
        if (writer == null) return;
        long abs = System.currentTimeMillis();
        long rel = abs - sessionStartMs;

        String safeDetail = detail == null ? "" : detail
                .replace("\"", "\"\"")
                .replace("\n", " ")
                .replace("\r", " ");

        // Все frame-колонки пустые. detail в кавычках на случай запятой.
        String row = rel + "," + abs + ",event,,,,,,,," +
                eventType + ",\"" + safeDetail + "\"";

        synchronized (LOCK) {
            try {
                writer.write(row);
                writer.newLine();
                // События редкие — flush сразу, чтобы при крэше не потерять.
                writer.flush();
            } catch (IOException e) {
                logger.error("Event write error", e);
            }
        }
    }

    public static void stopSession() {
        if (writer == null) return;
        event("session_stop", "");
        synchronized (LOCK) {
            if (writer != null) {
                try {
                    writer.flush();
                    writer.close();
                } catch (IOException e) {
                    logger.error("Cannot close stats", e);
                }
                writer = null;
            }
        }
        frameCounter.set(0);
        logger.info("Stats session closed");
    }

    public static void flush() {
        synchronized (LOCK) {
            if (writer != null) {
                try { writer.flush(); } catch (IOException ignored) {}
            }
        }
    }

    public static void setFrameSampleEvery(int n) {
        frameSampleEvery = Math.max(1, n);
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.4f", v);
    }
}