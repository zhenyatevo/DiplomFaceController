package com.example.diplomfacecontroller.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Детектор подъёма бровей как триггерного события.
 *
 * Принцип работы:
 *  1. Первые BASELINE_FRAMES кадров просто копит значения brow-eye distance,
 *     считает медиану — это "нейтральный уровень".
 *  2. Дальше использует гистерезис:
 *     - переход "не активен → активен": среднее значение по обеим бровям
 *       должно превысить baseline * (1 + RISE_THRESHOLD) И продержаться
 *       MIN_RISE_FRAMES кадров подряд (защита от мгновенного шума).
 *     - переход "активен → не активен": значение должно упасть ниже
 *       baseline * (1 + FALL_THRESHOLD).
 *  3. На переходе "не активен → активен" эмитится одно событие триггера
 *     (rising edge). После этого включается КУЛДАУН — следующий триггер
 *     возможен не раньше чем через TRIGGER_COOLDOWN_MS.
 *  4. Baseline продолжает медленно адаптироваться, но ТОЛЬКО когда брови
 *     не активны (иначе он уплывал бы за пользователем).
 */
public class BrowTriggerDetector {

    private static final Logger logger = LoggerFactory.getLogger(BrowTriggerDetector.class);

    // ===== Параметры — подобраны эмпирически =====
    /** Сколько кадров нужно для установки baseline. */
    private static final int BASELINE_FRAMES = 30;     // ~1 сек при 30 fps

    /** На сколько процентов выше baseline считаем брови "поднятыми".
     *  0.20 = на 20%. Для большинства людей "осознанное" поднятие
     *  бровей даёт +30-60%, а микро-движения <10%. */
    private static final double RISE_THRESHOLD = 0.12;

    /** Порог сброса (гистерезис): пока выше — считаем активным.
     *  Должен быть МЕНЬШЕ RISE_THRESHOLD. */
    private static final double FALL_THRESHOLD = 0.07;

    /** Сколько кадров подряд должно держаться превышение, чтобы стало "активно". */
    private static final int MIN_RISE_FRAMES = 3;      // ~130 мс

    /** Минимальный интервал между триггерами (мс). */
    private static final long TRIGGER_COOLDOWN_MS = 800;

    /** Скорость медленной адаптации baseline (когда не активен). */
    private static final double BASELINE_EMA_ALPHA = 0.005;

    // ===== Состояние =====
    private final Deque<Double> baselineBuffer = new ArrayDeque<>();
    private double baseline = 0;
    private boolean baselineReady = false;

    private boolean active = false;
    private int risingFrames = 0;

    private long lastTriggerTime = 0;

    private int debugFrameCount = 0;

    /**
     * Обрабатывает один кадр. Возвращает true ровно на одном кадре,
     * когда происходит rising edge (брови поднялись).
     *
     * @param leftBrowDist   нормализованное расстояние левая бровь → глаз
     * @param rightBrowDist  то же для правой
     * @param eyesValid      false если в кадре моргание/нет глаз — тогда
     *                       детектор приостанавливается, состояние не меняется
     * @return true если это событие триггера (один кадр), false иначе
     */
    public boolean process(double leftBrowDist, double rightBrowDist, boolean eyesValid) {
        debugFrameCount++;

        // Защита: если глаза закрыты или невалидны — данные о бровях ненадёжны
        if (!eyesValid || leftBrowDist <= 0.001 || rightBrowDist <= 0.001) {
            return false;
        }

        double current = (leftBrowDist + rightBrowDist) / 2.0;

        // ===== ФАЗА 1: накопление baseline =====
        if (!baselineReady) {
            baselineBuffer.addLast(current);
            if (baselineBuffer.size() >= BASELINE_FRAMES) {
                // Берём медиану накопленных значений
                double[] arr = baselineBuffer.stream().mapToDouble(Double::doubleValue).toArray();
                java.util.Arrays.sort(arr);
                baseline = arr[arr.length / 2];
                baselineReady = true;
                baselineBuffer.clear();
                logger.info("[Brow] Baseline calibrated: {}", String.format("%.3f", baseline));
            }
            return false;
        }

        double riseThr = baseline * (1.0 + RISE_THRESHOLD);
        double fallThr = baseline * (1.0 + FALL_THRESHOLD);

        boolean triggered = false;

        if (!active) {
            // Не активны: ждём устойчивого превышения порога подъёма
            if (current >= riseThr) {
                risingFrames++;
                if (risingFrames >= MIN_RISE_FRAMES) {
                    long now = System.currentTimeMillis();
                    if (now - lastTriggerTime >= TRIGGER_COOLDOWN_MS) {
                        triggered = true;
                        lastTriggerTime = now;
                        logger.info("[Brow] TRIGGER: current={} baseline={} (+{}%)",
                                String.format("%.3f", current),
                                String.format("%.3f", baseline),
                                String.format("%.1f", (current / baseline - 1.0) * 100));
                    }
                    active = true;
                    risingFrames = 0;
                }
            } else {
                risingFrames = 0;
                // Медленная адаптация baseline (только когда брови опущены)
                baseline = baseline * (1.0 - BASELINE_EMA_ALPHA) + current * BASELINE_EMA_ALPHA;
            }
        } else {
            // Активны: ждём падения ниже порога сброса (гистерезис)
            if (current < fallThr) {
                active = false;
                if (debugFrameCount % 30 == 0) {
                    logger.debug("[Brow] released, current={}", String.format("%.3f", current));
                }
            }
        }

        return triggered;
    }

    /** Сброс детектора — например при перезапуске трекинга. */
    public void reset() {
        baselineBuffer.clear();
        baseline = 0;
        baselineReady = false;
        active = false;
        risingFrames = 0;
        lastTriggerTime = 0;
        debugFrameCount = 0;
        logger.info("[Brow] Detector reset");
    }

    public boolean isActive() { return active; }
    public boolean isBaselineReady() { return baselineReady; }
    public double getBaseline() { return baseline; }
}