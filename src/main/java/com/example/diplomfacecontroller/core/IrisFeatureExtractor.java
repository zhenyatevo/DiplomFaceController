package com.example.diplomfacecontroller.core;

public class IrisFeatureExtractor {

    // Индексы в Face Mesh 478 (с refine_landmarks=True)
    // Центры радужки
    private static final int L_IRIS = 468;
    private static final int R_IRIS = 473;

    // Точки для EAR (Eye Aspect Ratio) — определение моргания
    private static final int L_TOP = 159, L_BOT = 145, L_LEFT = 33,  L_RIGHT = 133;
    private static final int R_TOP = 386, R_BOT = 374, R_LEFT = 263, R_RIGHT = 362;

    // ===== БРОВИ (MediaPipe FaceMesh, top-down) =====
    // Центральные точки бровей. У MediaPipe Y растёт ВНИЗ,
    // поэтому "брови подняты" = их Y МЕНЬШЕ обычного (и расстояние brow→eye БОЛЬШЕ).
    //
    // Левая бровь (правая для зрителя): 105 — центр, 70 — внутренний угол, 107 — над глазом
    // Правая бровь (левая для зрителя): 334 — центр, 300 — внутренний угол, 336 — над глазом
    //
    // Точки L_TOP=159 и R_TOP=386 — верхнее веко, их используем как опорные.
    private static final int L_BROW_CENTER = 105;
    private static final int L_BROW_INNER  = 107;
    private static final int R_BROW_CENTER = 334;
    private static final int R_BROW_INNER  = 336;

    /**
     * Извлекает признаки из массива landmarks.
     *
     * @param landmarks float[] размером 478*3
     * @return float[8]:
     *         [0] = левый iris X  (0..1)
     *         [1] = левый iris Y  (0..1)
     *         [2] = правый iris X (0..1)
     *         [3] = правый iris Y (0..1)
     *         [4] = EAR левого глаза  (< 0.20 = моргание)
     *         [5] = EAR правого глаза (< 0.20 = моргание)
     *         [6] = расстояние левая_бровь → левый_глаз (нормализованное на ширину глаза)
     *         [7] = расстояние правая_бровь → правый_глаз (нормализованное на ширину глаза)
     */
    public float[] extract(float[] landmarks) {
        if (landmarks == null || landmarks.length < 478 * 3) {
            return new float[]{0, 0, 0, 0, 0.3f, 0.3f, 0.0f, 0.0f};
        }

        float lIrisX = landmarks[L_IRIS * 3];
        float lIrisY = landmarks[L_IRIS * 3 + 1];
        float rIrisX = landmarks[R_IRIS * 3];
        float rIrisY = landmarks[R_IRIS * 3 + 1];

        float leftEAR  = computeEAR(landmarks, L_TOP, L_BOT, L_LEFT, L_RIGHT);
        float rightEAR = computeEAR(landmarks, R_TOP, R_BOT, R_LEFT, R_RIGHT);

        float leftBrowDist  = computeBrowEyeDistance(landmarks, L_BROW_CENTER, L_BROW_INNER,
                L_TOP, L_LEFT, L_RIGHT);
        float rightBrowDist = computeBrowEyeDistance(landmarks, R_BROW_CENTER, R_BROW_INNER,
                R_TOP, R_LEFT, R_RIGHT);

        return new float[]{lIrisX, lIrisY, rIrisX, rIrisY, leftEAR, rightEAR,
                leftBrowDist, rightBrowDist};
    }

    /**
     * Eye Aspect Ratio = вертикальное расстояние / (2 * горизонтальное)
     */
    private float computeEAR(float[] lm, int top, int bot, int left, int right) {
        float dy = Math.abs(lm[top * 3 + 1] - lm[bot * 3 + 1]);
        float dx = Math.abs(lm[left * 3]    - lm[right * 3]);
        return dx > 0.001f ? dy / (2.0f * dx) : 0.3f;
    }

    /**
     * Расстояние от центра брови до верхнего века (вертикальное),
     * нормализованное на ширину глаза. Усредняется по двум точкам брови
     * (центр + внутренний угол) для устойчивости.
     *
     * Чем БОЛЬШЕ значение — тем сильнее подняты брови.
     * Нормализация на ширину глаза делает признак инвариантным к расстоянию до камеры.
     *
     * Типичные значения:
     *   нейтрально: ~0.30 - 0.45
     *   подняты:    ~0.55 - 0.80
     */
    private float computeBrowEyeDistance(float[] lm, int browCenter, int browInner,
                                         int eyeTop, int eyeLeft, int eyeRight) {
        // Y брови (среднее из двух точек)
        float browY = (lm[browCenter * 3 + 1] + lm[browInner * 3 + 1]) / 2f;
        // Y верхнего века
        float eyeY  = lm[eyeTop * 3 + 1];
        // Вертикальное расстояние (положительное = бровь выше глаза)
        float vDist = Math.abs(browY - eyeY);
        // Ширина глаза для нормализации
        float eyeWidth = Math.abs(lm[eyeLeft * 3] - lm[eyeRight * 3]);
        return eyeWidth > 0.001f ? vDist / eyeWidth : 0.0f;
    }
}