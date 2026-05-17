package com.example.diplomfacecontroller.core;

import com.example.diplomfacecontroller.models.GazeData;
import javafx.geometry.Point2D;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;
import org.opencv.objdetect.CascadeClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class GazeEstimator {
    private static final Logger logger = LoggerFactory.getLogger(GazeEstimator.class);

    // ===== Haar (старый режим — fallback) =====
    private CascadeClassifier eyeDetector;
    private boolean detectorLoaded;

    // ===== MediaPipe (новый режим) =====
    private MediaPipeBridge    mediaPipeBridge;
    private IrisFeatureExtractor irisExtractor;
    private boolean            neuralModeActive = false;

    // ===== Детектор поднятия бровей (триггер для клавиатуры) =====
    private final BrowTriggerDetector browDetector = new BrowTriggerDetector();

    // ===== Общие поля =====
    private long   lastBlinkTime;
    private int    blinkCount;
    private Point2D rawGaze;
    private GazeData lastGazeData;

    private int frameCount       = 0;
    private int eyesFoundCount   = 0;
    private int eyesNotFoundCount = 0;

    // ===== АВТОКАЛИБРОВКА ЦЕНТРА РАДУЖКИ (нейронный режим) =====
    // Раньше центр X/Y был жёстко прописан константами, которые приходилось
    // угадывать под конкретную камеру и посадку — и они почти никогда не
    // совпадали с реальностью (Y улетал в насыщение ±1).
    //
    // Теперь первые AUTOCAL_FRAMES валидных кадров (лицо реально поймано —
    // в отличие от старого adaptCenterY, который копил мусор с прогрева камеры)
    // накапливаем сырые rawX/rawY и берём МЕДИАНУ как центр. После этого
    // центр лочится. resetFilters() запускает автокалибровку заново.
    //
    // ВАЖНО про range: он НЕ автокалибруется, а задан фиксированно широким
    // (см. поля rangeX/rangeY ниже). Попытка измерить range по первым кадрам
    // проваливалась — за ~1.5 сек пользователь смотрит почти в одну точку,
    // размах выходит ~0.03, и gaze упирается в ±1 на середине хода глаз.
    // Широкий фиксированный range безопасен: калибровка по 9 точкам сама
    // подгоняет наклон. По реальным логам rawX ходит в пределах ~0.08,
    // rawY ~0.15 — берём с хорошим запасом.
    private static final int AUTOCAL_FRAMES = 45;          // ~1.5-2 сек при 25-30 fps
    private final java.util.List<Double> autoCalRawX = new java.util.ArrayList<>();
    private final java.util.List<Double> autoCalRawY = new java.util.ArrayList<>();
    private boolean autoCalDone = false;
    private double centerX = 0.61, centerY = 0.30;   // стартовые значения до автокалибровки
    // Широкий фиксированный диапазон — gaze почти не упирается в ±1 до калибровки.
    private double rangeX  = 0.12, rangeY  = 0.15;

    // ===== МЕДЛЕННАЯ АДАПТАЦИЯ ЦЕНТРА (защита от дрейфа головы) =====
    // После автокалибровки центр залочен. Но за время работы пользователь
    // понемногу смещается/оседает — "нейтраль" радужки уезжает, и gaze
    // постепенно уходит в сторону ("теряет стабильность со временем").
    //
    // Решение: центр ОЧЕНЬ медленно подтягивается к текущему rawX/rawY —
    // но ТОЛЬКО когда взгляд близко к нейтрали (|gaze| < DRIFT_NEUTRAL_ZONE).
    // Если адаптировать при любом взгляде — центр "уплывёт за взглядом", как
    // это делал старый сломанный adaptCenterY. Адаптация только у центра
    // ловит именно дрейф позы, а не движение глаз.
    private static final double DRIFT_EMA_ALPHA   = 0.0015; // скорость подтяжки
    private static final double DRIFT_NEUTRAL_ZONE = 0.25;  // |gaze| ниже — считаем "смотрит в центр"

    // Диагностика: накопленные min/max сырых координат — чтобы видеть
    // реальный рабочий диапазон радужки в логах.
    private double dbgMinRawX =  Double.POSITIVE_INFINITY, dbgMaxRawX = Double.NEGATIVE_INFINITY;
    private double dbgMinRawY =  Double.POSITIVE_INFINITY, dbgMaxRawY = Double.NEGATIVE_INFINITY;

    // Отбраковка выбросов (Haar-режим)
    private double prevLeftX = 0, prevLeftY = 0;
    private double prevRightX = 0, prevRightY = 0;
    private static final double MAX_GAZE_STEP = 0.3;

    // Адаптивное сглаживание (оба режима)
    private double prevX = 0, prevY = 0;

    // ===== Калибровка =====
    private CalibrationParameters          calibrationParams;
    private QuadraticCalibrationParameters quadParams;
    private boolean useQuadratic = false;
    private final double[] histX = new double[5];
    private final double[] histY = new double[5];
    private int histIdx = 0;

    // Добавь метод:
    private Point2D medianFilter(double x, double y) {
        histX[histIdx] = x;
        histY[histIdx] = y;
        histIdx = (histIdx + 1) % histX.length;

        double[] sx = histX.clone();
        double[] sy = histY.clone();
        java.util.Arrays.sort(sx);
        java.util.Arrays.sort(sy);
        return new Point2D(sx[sx.length / 2], sy[sy.length / 2]);
    }

    public GazeEstimator() {
        this.rawGaze          = new Point2D(0, 0);
        this.calibrationParams = new CalibrationParameters(1, 0, 1, 0);
        this.lastGazeData     = new GazeData();
        this.lastBlinkTime    = System.currentTimeMillis();
        this.irisExtractor    = new IrisFeatureExtractor();
        initDetector();
    }

    // ================================================================
    //  ИНИЦИАЛИЗАЦИЯ
    // ================================================================

    private void initDetector() {
        try {
            eyeDetector   = new CascadeClassifier();
            detectorLoaded = loadCascade(eyeDetector, "/cascades/haarcascade_eye.xml", "eye");
            if (detectorLoaded) logger.info("Eye detector loaded successfully");
            else                logger.error("Failed to load eye detector");
        } catch (Exception e) {
            logger.error("Failed to load eye detector: {}", e.getMessage(), e);
        }
    }

    private boolean loadCascade(CascadeClassifier detector, String resourcePath, String name) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                logger.error("{} cascade not found: {}", name, resourcePath);
                return false;
            }
            File tmp = File.createTempFile("opencv_" + name + "_", ".xml");
            tmp.deleteOnExit();
            Files.copy(is, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return detector.load(tmp.getAbsolutePath());
        } catch (Exception e) {
            logger.error("Error loading {} cascade: {}", name, e.getMessage());
            return false;
        }
    }

    // ================================================================
    //  НЕЙРОННЫЙ РЕЖИМ (MediaPipe)
    // ================================================================

    /**
     * Запустить MediaPipe bridge. Вызывать из MainController.initializeComponents()
     * в отдельном потоке, т.к. старт занимает ~4 секунды.
     */
    public void startNeuralMode() {
        new Thread(() -> {
            try {
                mediaPipeBridge = new MediaPipeBridge();
                mediaPipeBridge.start();
                neuralModeActive = true;
                logger.info("Neural gaze mode ACTIVE");
            } catch (Exception e) {
                logger.error("Failed to start neural mode, falling back to Haar: {}", e.getMessage());
                neuralModeActive = false;
            }
        }, "NeuralModeStartThread").start();
    }

    /**
     * Остановить MediaPipe bridge. Вызывать из stopTracking().
     */
    public void stopNeuralMode() {
        neuralModeActive = false;
        if (mediaPipeBridge != null) {
            mediaPipeBridge.stop();
            mediaPipeBridge = null;
        }
        browDetector.reset();
        logger.info("Neural gaze mode stopped");
    }

    /** Публичный доступ к детектору бровей — пригодится UI для индикации
     *  "baseline ещё калибруется" / "брови подняты". */
    public BrowTriggerDetector getBrowDetector() {
        return browDetector;
    }

    // ================================================================
    //  СОВМЕСТИМОСТЬ С CalibrationManager
    //  Центр и диапазон радужки определяются АВТОКАЛИБРОВКОЙ (первые
    //  AUTOCAL_FRAMES валидных кадров после resetFilters), а не задаются
    //  константами и не подвергаются постоянной EMA-адаптации. Поэтому
    //  setCenterLocked не имеет смыслового эффекта — оставлен как no-op,
    //  чтобы CalibrationManager компилировался без изменений.
    //
    //  resetFilters сбрасывает сглаживатели, детектор бровей И запускает
    //  автокалибровку заново — это происходит во время warmup-фазы
    //  калибровки, когда пользователь смотрит в центр экрана.
    // ================================================================

    /** Сброс фильтров перед калибровкой. */
    public void resetFilters() {
        // Сглаживатели
        prevX = 0; prevY = 0;
        // Медианный буфер
        histIdx = 0;
        // Детектор бровей — пусть тоже перекалибрует baseline под текущего пользователя
        browDetector.reset();
        // Сам raw gaze
        rawGaze = new Point2D(0, 0);
        // Автокалибровка центра/диапазона — пересобрать заново.
        // Это произойдёт во время warmup-фазы калибровки (пользователь смотрит
        // в центр экрана) — идеальный момент, чтобы определить "центр" радужки.
        autoCalDone = false;
        autoCalRawX.clear();
        autoCalRawY.clear();
        // Сброс диагностического диапазона
        dbgMinRawX =  Double.POSITIVE_INFINITY; dbgMaxRawX = Double.NEGATIVE_INFINITY;
        dbgMinRawY =  Double.POSITIVE_INFINITY; dbgMaxRawY = Double.NEGATIVE_INFINITY;
        logger.info("GazeEstimator filters reset (auto-cal will re-run)");
    }

    /**
     * Заглушка для совместимости. В текущей реализации центр радужки
     * жёстко задан константами в analyzeGazeNeural и не подвергается EMA-адаптации,
     * поэтому "блокировать" нечего.
     */
    public void setCenterLocked(boolean locked) {
        // no-op в этой версии; логируем для дебага
        logger.debug("setCenterLocked({}) called — no-op in this build", locked);
    }

    public boolean isNeuralModeActive() {
        return neuralModeActive && mediaPipeBridge != null && mediaPipeBridge.isConnected();
    }

    // ================================================================
    //  ОСНОВНОЙ МЕТОД АНАЛИЗА — вызывается из CameraManager
    //  Автоматически выбирает нейронный или Haar-режим
    // ================================================================

    public GazeData analyzeGaze(Mat frame) {
        if (isNeuralModeActive()) {
            return analyzeGazeNeural(frame);
        } else {
            return analyzeGazeHaar(frame);
        }
    }

    // ================================================================
    //  НЕЙРОННЫЙ АНАЛИЗ
    // ================================================================

    private GazeData analyzeGazeNeural(Mat frame) {
        frameCount++;
        GazeData gd = new GazeData();

        float[] landmarks = mediaPipeBridge.processFrame(frame);
        if (landmarks == null) {
            return lastGazeData;
        }

        float[] iris = irisExtractor.extract(landmarks);

        // Среднее между двумя зрачками
        boolean blinkingNow = (iris[4] < 0.10f) || (iris[5] < 0.10f);
        if (blinkingNow) {
            boolean eyesValid = false;
            boolean trigger = browDetector.process(iris[6], iris[7], eyesValid);
            gd.setBrowsRaised(browDetector.isActive());
            gd.setBrowTriggerEvent(trigger);
            if (lastGazeData != null) {
                gd.setCombinedGaze(lastGazeData.getCombinedGaze());
                gd.setLeftEyeGaze(lastGazeData.getLeftEyeGaze());
                gd.setRightEyeGaze(lastGazeData.getRightEyeGaze());
            }
            gd.setLeftEyeClosed(true);
            gd.setRightEyeClosed(true);
            long nowBlink = System.currentTimeMillis();
            if (nowBlink - lastBlinkTime > 150) {
                blinkCount++;
                gd.setLastBlinkTime(nowBlink);
                lastBlinkTime = nowBlink;
            }
            lastGazeData = gd;
            return gd;
        }

        double rawX = (iris[0] + iris[2]) / 2.0;
        double rawY = (iris[1] + iris[3]) / 2.0;

        // ===== ДИАГНОСТИКА: накапливаем реальный min/max сырых координат =====
        // Это даёт точную картину рабочего диапазона радужки именно для
        // этой камеры и посадки — больше не нужно угадывать константы.
        if (rawX < dbgMinRawX) dbgMinRawX = rawX;
        if (rawX > dbgMaxRawX) dbgMaxRawX = rawX;
        if (rawY < dbgMinRawY) dbgMinRawY = rawY;
        if (rawY > dbgMaxRawY) dbgMaxRawY = rawY;

        // ===== АВТОКАЛИБРОВКА ТОЛЬКО ЦЕНТРА =====
        // Первые AUTOCAL_FRAMES валидных кадров копим сырые значения и берём
        // МЕДИАНУ как центр. Лицо здесь уже реально поймано (мы дошли до этой
        // точки только если landmarks != null и нет моргания), поэтому мусор
        // с прогрева не попадает — в отличие от старого adaptCenterY.
        //
        // ВАЖНО: range (диапазон) здесь НЕ измеряем. Раньше пробовали брать
        // 10-90 перцентиль за первые ~1.5 сек — но за это время пользователь
        // смотрит примерно в одну точку, и размах выходит крошечный (~0.03).
        // Тогда gazeX/gazeY упирается в ±1 уже на середине реального хода глаз,
        // и половина экрана становится недостижимой.
        //
        // Поэтому range фиксированный и заведомо ШИРОКИЙ (см. поля rangeX/rangeY).
        // Это безопасно: калибровка по 9 точкам отлично компенсирует слишком
        // широкий диапазон (просто коэффициент наклона будет больше). А вот
        // слишком узкий диапазон калибровка исправить НЕ может — там сигнал
        // уже убит насыщением в ±1.
        if (!autoCalDone) {
            autoCalRawX.add(rawX);
            autoCalRawY.add(rawY);
            if (autoCalRawX.size() >= AUTOCAL_FRAMES) {
                double[] xs = autoCalRawX.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                double[] ys = autoCalRawY.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                // Центр — медиана накопленных значений.
                centerX = xs[xs.length / 2];
                centerY = ys[ys.length / 2];
                // range НЕ трогаем — остаётся широким значением из полей класса.
                autoCalDone = true;
                autoCalRawX.clear();
                autoCalRawY.clear();
                logger.info("[GazeEst] AUTO-CAL done: centerX={} centerY={} rangeX={} rangeY={}",
                        String.format("%.3f", centerX), String.format("%.3f", centerY),
                        String.format("%.3f", rangeX),  String.format("%.3f", rangeY));
            }
        }

        double gazeX = (rawX - centerX) / rangeX;
        double gazeY = (rawY - centerY) / rangeY;

        // ===== МЕДЛЕННАЯ АДАПТАЦИЯ ЦЕНТРА против дрейфа головы =====
        // Когда взгляд близко к нейтрали — потихоньку подтягиваем центр к
        // текущему raw. Это компенсирует медленное смещение позы, но не
        // реагирует на нормальное движение глаз по экрану (там |gaze| большой).
        if (autoCalDone
                && Math.abs(gazeX) < DRIFT_NEUTRAL_ZONE
                && Math.abs(gazeY) < DRIFT_NEUTRAL_ZONE) {
            centerX += DRIFT_EMA_ALPHA * (rawX - centerX);
            centerY += DRIFT_EMA_ALPHA * (rawY - centerY);
        }

        // Ограничение диапазона
        gazeX = Math.max(-1, Math.min(1, gazeX));
        gazeY = Math.max(-1, Math.min(1, gazeY));

        // Сглаживание
        Point2D filtered = medianFilter(gazeX, gazeY);
        this.rawGaze = smooth(filtered);

        gd.setLeftEyeGaze(new Point2D(iris[0] * 2 - 1, iris[1] * 2 - 1));
        gd.setRightEyeGaze(new Point2D(iris[2] * 2 - 1, iris[3] * 2 - 1));
        gd.setCombinedGaze(rawGaze);

        boolean leftClosed  = iris[4] < 0.18f;
        boolean rightClosed = iris[5] < 0.18f;
        gd.setLeftEyeClosed(leftClosed);
        gd.setRightEyeClosed(rightClosed);

        // ===== ТРИГГЕР ПО БРОВЯМ =====
        // iris[6], iris[7] — нормализованное расстояние бровь→глаз для левой и правой.
        // Детектор приостанавливается при моргании (eyesValid=false), чтобы
        // не путать "глаза закрылись" с "брови опустились".
        // eyesValid: глаза открыты. Доп. защита в BrowTriggerDetector через min browDist
        boolean eyesValid = !leftClosed && !rightClosed;
        boolean trigger = browDetector.process(iris[6], iris[7], eyesValid);
        gd.setBrowsRaised(browDetector.isActive());
        gd.setBrowTriggerEvent(trigger);

        if (leftClosed && rightClosed) {
            long now = System.currentTimeMillis();
            if (now - lastBlinkTime > 150) {
                blinkCount++;
                gd.setLastBlinkTime(now);
                lastBlinkTime = now;
            }
        }

        if (frameCount % 10 == 0) {
            // Логируем базовую линию и текущее значение бровей — удобно для диагностики
            double browCurrent = (iris[6] + iris[7]) / 2.0;
            double browBaseline = browDetector.isBaselineReady() ? browDetector.getBaseline() : 0.0;
            double browRatio = 1.0;

            if (browBaseline > 0.0001) {
                browRatio = browCurrent / browBaseline;
            }

            gd.setBrowRatio(browRatio);
            logger.info("[Neural] gaze=({},{}) EAR L={} R={} brow curr={} base={} ratio={}% active={} baseReady={}",
                    String.format("%.2f", gazeX), String.format("%.2f", gazeY),
                    String.format("%.2f", iris[4]), String.format("%.2f", iris[5]),
                    String.format("%.3f", browCurrent),
                    String.format("%.3f", browBaseline),
                    browBaseline > 0 ? String.format("%.0f", browCurrent / browBaseline * 100) : "N/A",
                    browDetector.isActive(),
                    browDetector.isBaselineReady());

            // Диагностика сырых координат — реальный рабочий диапазон радужки.
            // По этим строкам видно, в каких пределах ходят rawX/rawY у ЭТОЙ
            // камеры и посадки. Полезно если автокалибровка дала плохой результат.
            logger.info("[GazeEst] raw=({},{}) rawRange X[{}..{}] Y[{}..{}] center=({},{}) range=({},{}) autoCalDone={}",
                    String.format("%.3f", rawX), String.format("%.3f", rawY),
                    String.format("%.3f", dbgMinRawX), String.format("%.3f", dbgMaxRawX),
                    String.format("%.3f", dbgMinRawY), String.format("%.3f", dbgMaxRawY),
                    String.format("%.3f", centerX), String.format("%.3f", centerY),
                    String.format("%.3f", rangeX),  String.format("%.3f", rangeY),
                    autoCalDone);
        }

        this.lastGazeData = gd;
        return gd;
    }

    // ================================================================
    //  HAAR-АНАЛИЗ
    // ================================================================

    private GazeData analyzeGazeHaar(Mat eyeFrame) {
        GazeData gazeData = new GazeData();
        frameCount++;

        if (eyeFrame == null || eyeFrame.empty()) {
            this.lastGazeData = gazeData;
            return gazeData;
        }
        if (!detectorLoaded) {
            this.lastGazeData = gazeData;
            return gazeData;
        }

        try {
            Mat gray = new Mat();
            Imgproc.cvtColor(eyeFrame, gray, Imgproc.COLOR_BGR2GRAY);

            MatOfRect eyes = new MatOfRect();
            eyeDetector.detectMultiScale(gray, eyes);
            Rect[] eyesArray = eyes.toArray();

            if (eyesArray.length > 0) eyesFoundCount++;
            else                      eyesNotFoundCount++;

            if (frameCount % 30 == 0) {
                logger.info("[Haar] Frame {}: {} eyes found / {} not found",
                        frameCount, eyesFoundCount, eyesNotFoundCount);
            }

            if (eyesArray.length == 0) {
                this.rawGaze = new Point2D(0, 0);
                gazeData.setCombinedGaze(new Point2D(0, 0));
                this.lastGazeData = gazeData;
                return gazeData;
            }

            for (int i = 0; i < eyesArray.length; i++) {
                Rect eye = eyesArray[i];
                Imgproc.rectangle(eyeFrame, eye, new Scalar(0, 255, 0), 1);

                Mat eyeROI   = new Mat(gray, eye);
                Mat equalized = new Mat();
                Imgproc.equalizeHist(eyeROI, equalized);

                Point pupilPos = findPupilCenter(equalized);
                Imgproc.circle(eyeFrame,
                        new Point(eye.x + pupilPos.x, eye.y + pupilPos.y),
                        3, new Scalar(0, 0, 255), -1);

                double normX    = pupilPos.x / eye.width;
                double normY    = pupilPos.y / eye.height;
                double rawGazeX = Math.max(-1, Math.min(1, normX * 2 - 1));
                double rawGazeY = Math.max(-1, Math.min(1, normY * 2 - 1));

                double finalX = rawGazeX, finalY = rawGazeY;
                if (i == 0) {
                    if (Math.abs(rawGazeX - prevLeftX) > MAX_GAZE_STEP ||
                            Math.abs(rawGazeY - prevLeftY) > MAX_GAZE_STEP) {
                        finalX = prevLeftX; finalY = prevLeftY;
                    } else { prevLeftX = rawGazeX; prevLeftY = rawGazeY; }
                    gazeData.setLeftEyeGaze(new Point2D(finalX, finalY));
                } else if (i == 1) {
                    if (Math.abs(rawGazeX - prevRightX) > MAX_GAZE_STEP ||
                            Math.abs(rawGazeY - prevRightY) > MAX_GAZE_STEP) {
                        finalX = prevRightX; finalY = prevRightY;
                    } else { prevRightX = rawGazeX; prevRightY = rawGazeY; }
                    gazeData.setRightEyeGaze(new Point2D(finalX, finalY));
                }
            }

            detectBlink(gazeData, eyesArray.length);

            Point2D combined = computeCombinedGaze(gazeData);
            if (combined != null) {
                this.rawGaze = smooth(combined);
                gazeData.setCombinedGaze(combined);
            } else {
                this.rawGaze = new Point2D(0, 0);
                gazeData.setCombinedGaze(new Point2D(0, 0));
            }
            this.lastGazeData = gazeData;

        } catch (Exception e) {
            logger.error("Error in analyzeGazeHaar: {}", e.getMessage());
            this.rawGaze = new Point2D(0, 0);
            gazeData.setCombinedGaze(new Point2D(0, 0));
            this.lastGazeData = gazeData;
        }
        return gazeData;
    }

    private Point findPupilCenter(Mat eyeROI) {
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(eyeROI, blurred, new Size(5, 5), 0);
        Mat median = new Mat();
        Imgproc.medianBlur(blurred, median, 5);
        Mat thresh = new Mat();
        Imgproc.adaptiveThreshold(median, thresh, 255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, 11, 2);
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
        Imgproc.morphologyEx(thresh, thresh, Imgproc.MORPH_CLOSE, kernel);

        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(thresh, contours, new Mat(),
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        double maxArea = 0;
        MatOfPoint best = null;
        for (MatOfPoint c : contours) {
            double area = Imgproc.contourArea(c);
            if (area > maxArea && area > 10) { maxArea = area; best = c; }
        }
        if (best != null) {
            Moments m = Imgproc.moments(best);
            if (m.get_m00() != 0) {
                int cx = (int)(m.get_m10() / m.get_m00());
                int cy = (int)(m.get_m01() / m.get_m00());
                if (cx > 5 && cx < eyeROI.width()-5 && cy > 5 && cy < eyeROI.height()-5)
                    return new Point(cx, cy);
            }
        }
        return new Point(eyeROI.width() / 2.0, eyeROI.height() / 2.0);
    }

    private void detectBlink(GazeData gazeData, int eyesFound) {
        long now = System.currentTimeMillis();
        if (eyesFound < 2) {
            gazeData.setLeftEyeClosed(true);
            gazeData.setRightEyeClosed(true);
            if (now - lastBlinkTime > 100) {
                blinkCount++;
                gazeData.setLastBlinkTime(now);
                lastBlinkTime = now;
            }
        } else {
            gazeData.setLeftEyeClosed(false);
            gazeData.setRightEyeClosed(false);
        }
        gazeData.setBlinkRate((blinkCount * 60000.0) / (now - lastBlinkTime + 1));
    }

    private Point2D computeCombinedGaze(GazeData gd) {
        Point2D l = gd.getLeftEyeGaze(), r = gd.getRightEyeGaze();
        if (l == null && r == null) return new Point2D(0, 0);
        if (l == null) return r;
        if (r == null) return l;
        return new Point2D(
                Math.max(-1, Math.min(1, (l.getX() + r.getX()) / 2.0)),
                Math.max(-1, Math.min(1, (l.getY() + r.getY()) / 2.0))
        );
    }

    // ================================================================
    //  СГЛАЖИВАНИЕ И КАЛИБРОВКА
    // ================================================================

    private Point2D smooth(Point2D raw) {
        double dx    = Math.abs(raw.getX() - prevX);
        double dy    = Math.abs(raw.getY() - prevY);
        double speed = Math.sqrt(dx * dx + dy * dy);
        double alpha = Math.min(0.12, Math.max(0.02, 0.08 / (speed + 0.1)));  // замедлено: max 12% изменения за кадр
        double sx    = prevX + alpha * (raw.getX() - prevX);
        double sy    = prevY + alpha * (raw.getY() - prevY);
        prevX = sx; prevY = sy;
        return new Point2D(sx, sy);
    }

    public Point2D getRawGaze() { return rawGaze; }

    public Point2D getCalibratedGaze() {
        double rx = rawGaze.getX(), ry = rawGaze.getY();
        double cx, cy;
        if (useQuadratic && quadParams != null) {
            cx = quadParams.applyX(rx);
            cy = quadParams.applyY(ry);
        } else {
            cx = calibrationParams.applyX(rx);
            cy = calibrationParams.applyY(ry);
        }
        return new Point2D(
                Math.max(-1.2, Math.min(1.2, cx)),
                Math.max(-1.2, Math.min(1.2, cy))
        );
    }

    public void setCalibrationParams(CalibrationParameters params) {
        this.calibrationParams = params;
        this.useQuadratic = false;
        this.quadParams   = null;
        logger.info("Linear calibration set: {}", params);
    }

    public void setQuadraticCalibrationParams(QuadraticCalibrationParameters params) {
        this.quadParams       = params;
        this.useQuadratic     = true;
        this.calibrationParams = new CalibrationParameters(1, 0, 1, 0);
        logger.info("Quadratic calibration set: {}", params);
    }

    public void resetCalibration() {
        this.calibrationParams = new CalibrationParameters(1, 0, 1, 0);
        this.quadParams        = null;
        this.useQuadratic      = false;
    }

    public boolean isCalibrated() {
        if (useQuadratic && quadParams != null)
            return !(Math.abs(quadParams.ax2) < 0.001 &&
                    Math.abs(quadParams.ax1 - 1.0) < 0.001 &&
                    Math.abs(quadParams.ax0) < 0.001);
        return calibrationParams != null &&
                !(Math.abs(calibrationParams.getAx() - 1.0) < 0.001 &&
                        Math.abs(calibrationParams.getBx())        < 0.001 &&
                        Math.abs(calibrationParams.getAy() - 1.0) < 0.001 &&
                        Math.abs(calibrationParams.getBy())        < 0.001);
    }

    public boolean isDetectorLoaded()   { return detectorLoaded; }
    public boolean isQuadraticCalibrated() { return useQuadratic && quadParams != null; }
    public GazeData getLastGazeData()   { return lastGazeData; }
    public int getBlinkCount()          { return blinkCount; }
    public void resetBlinkCount()       { blinkCount = 0; lastBlinkTime = System.currentTimeMillis(); }

    // ================================================================
    //  ВНУТРЕННИЕ КЛАССЫ КАЛИБРОВКИ
    // ================================================================

    public static class CalibrationParameters {
        private final double ax, bx, ay, by;
        public CalibrationParameters(double ax, double bx, double ay, double by) {
            this.ax = ax; this.bx = bx; this.ay = ay; this.by = by;
        }
        public double applyX(double x) { return ax * x + bx; }
        public double applyY(double y) { return ay * y + by; }
        public double getAx() { return ax; } public double getBx() { return bx; }
        public double getAy() { return ay; } public double getBy() { return by; }
        @Override public String toString() {
            return String.format("X: %.3f*r+%.3f, Y: %.3f*r+%.3f", ax, bx, ay, by);
        }
    }

    public static class QuadraticCalibrationParameters {
        final double ax2, ax1, ax0, ay2, ay1, ay0;
        public QuadraticCalibrationParameters(double ax2, double ax1, double ax0,
                                              double ay2, double ay1, double ay0) {
            this.ax2=ax2; this.ax1=ax1; this.ax0=ax0;
            this.ay2=ay2; this.ay1=ay1; this.ay0=ay0;
        }
        public double applyX(double x) { return ax2*x*x + ax1*x + ax0; }
        public double applyY(double y) { return ay2*y*y + ay1*y + ay0; }
        @Override public String toString() {
            return String.format("X:%.3fx²+%.3fx+%.3f Y:%.3fy²+%.3fy+%.3f",
                    ax2,ax1,ax0,ay2,ay1,ay0);
        }
    }
}