package com.example.diplomfacecontroller.core;

public class IrisFeatureExtractor {

    // Индексы в Face Mesh 478 (с refine_landmarks=True)
    // Центры радужки
    private static final int L_IRIS = 468;
    private static final int R_IRIS = 473;

    // Точки для EAR (Eye Aspect Ratio) — определение моргания
    // Левый глаз: верхнее веко=159, нижнее=145, левый угол=33, правый угол=133
    // Правый глаз: верхнее веко=386, нижнее=374, левый угол=362, правый угол=263
    private static final int L_TOP = 159, L_BOT = 145, L_LEFT = 33,  L_RIGHT = 133;
    private static final int R_TOP = 386, R_BOT = 374, R_LEFT = 362, R_RIGHT = 263;

    /**
     * Извлекает 6 признаков из массива landmarks.
     *
     * @param landmarks float[] размером 478*3: [x0,y0,z0, x1,y1,z1, ...]
     *                  координаты нормализованы в диапазоне [0, 1]
     * @return float[6]:
     *         [0] = левый iris X  (0..1)
     *         [1] = левый iris Y  (0..1)
     *         [2] = правый iris X (0..1)
     *         [3] = правый iris Y (0..1)
     *         [4] = EAR левого глаза  (< 0.2 = моргание)
     *         [5] = EAR правого глаза (< 0.2 = моргание)
     */
    public float[] extract(float[] landmarks) {
        if (landmarks == null || landmarks.length < 478 * 3) {
            return new float[]{0, 0, 0, 0, 0.3f, 0.3f};
        }

        float lIrisX = landmarks[L_IRIS * 3];
        float lIrisY = landmarks[L_IRIS * 3 + 1];
        float rIrisX = landmarks[R_IRIS * 3];
        float rIrisY = landmarks[R_IRIS * 3 + 1];

        float leftEAR  = computeEAR(landmarks, L_TOP, L_BOT, L_LEFT, L_RIGHT);
        float rightEAR = computeEAR(landmarks, R_TOP, R_BOT, R_LEFT, R_RIGHT);

        return new float[]{lIrisX, lIrisY, rIrisX, rIrisY, leftEAR, rightEAR};
    }

    /**
     * Eye Aspect Ratio = вертикальное расстояние / (2 * горизонтальное)
     * При открытом глазе ~0.25-0.35, при закрытом < 0.20
     */
    private float computeEAR(float[] lm, int top, int bot, int left, int right) {
        float dy = Math.abs(lm[top * 3 + 1] - lm[bot * 3 + 1]);
        float dx = Math.abs(lm[left * 3]    - lm[right * 3]);
        return dx > 0.001f ? dy / (2.0f * dx) : 0.3f;
    }
}