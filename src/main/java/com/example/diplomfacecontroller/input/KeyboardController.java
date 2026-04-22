package com.example.diplomfacecontroller.input;

import com.example.diplomfacecontroller.models.GazeData;
import com.example.diplomfacecontroller.models.KeyboardKey;
import javafx.geometry.Rectangle2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

public class KeyboardController {
    private static final Logger logger = LoggerFactory.getLogger(KeyboardController.class);

    private List<KeyboardKey> keys;
    private StringBuilder currentText = new StringBuilder();
    private boolean shiftPressed = false;
    private KeyboardKey currentHoverKey = null;
    private long hoverStartTime = 0;
    private static final long HOVER_DURATION = 800; // мс для набора
    private static final long BACKSPACE_HOVER = 400; // мс для backspace

    // Параметры клавиатуры
    private double keyboardX = 50;
    private double keyboardY = 300;
    private double keyWidth = 60;
    private double keyHeight = 60;
    private double keySpacing = 5;

    private Canvas keyboardCanvas;
    private boolean keyboardVisible = false;

    // Робот для эмуляции клавиатуры (опционально)
    private Robot robot;

    public KeyboardController() {
        try {
            robot = new Robot();
        } catch (AWTException e) {
            logger.error("Failed to initialize Robot", e);
        }
        initKeyboard();
    }

    private void initKeyboard() {
        keys = new ArrayList<>();

        // Раскладка клавиш (4 ряда)
        String[][] rows = {
                {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "[", "]", "\\"},
                {"a", "s", "d", "f", "g", "h", "j", "k", "l", ";", "'"},
                {"z", "x", "c", "v", "b", "n", "m", ",", ".", "/"},
                {" ", " ", " ", " ", " ", " ", " ", " ", " ", " "} // Специальные клавиши
        };

        String[][] shiftRows = {
                {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "{", "}", "|"},
                {"A", "S", "D", "F", "G", "H", "J", "K", "L", ":", "\""},
                {"Z", "X", "C", "V", "B", "N", "M", "<", ">", "?"},
                {" ", " ", " ", " ", " ", " ", " ", " ", " ", " "}
        };

        // Создаем клавиши
        for (int row = 0; row < rows.length; row++) {
            double y = keyboardY + row * (keyHeight + keySpacing);

            // Центрируем каждый ряд
            double rowWidth = rows[row].length * (keyWidth + keySpacing) - keySpacing;
            double startX = keyboardX + (800 - rowWidth) / 2; // 800 - ширина области

            for (int col = 0; col < rows[row].length; col++) {
                double x = startX + col * (keyWidth + keySpacing);
                Rectangle2D bounds = new Rectangle2D(x, y, keyWidth, keyHeight);

                if (row < 3) { // Обычные клавиши
                    keys.add(new KeyboardKey(rows[row][col], shiftRows[row][col], bounds));
                } else { // Специальные клавиши
                    switch (col) {
                        case 0:
                            KeyboardKey shiftKey = new KeyboardKey("SHIFT", bounds);
                            keys.add(shiftKey);
                            break;
                        case 2:
                            KeyboardKey spaceKey = new KeyboardKey(" ", " ", bounds);
                            spaceKey = new KeyboardKey("SPACE", bounds);
                            keys.add(spaceKey);
                            break;
                        case 5:
                            KeyboardKey enterKey = new KeyboardKey("ENTER", bounds);
                            keys.add(enterKey);
                            break;
                        case 8:
                            KeyboardKey backspaceKey = new KeyboardKey("BACKSPACE", bounds);
                            keys.add(backspaceKey);
                            break;
                        case 9:
                            KeyboardKey clearKey = new KeyboardKey("CLEAR", bounds);
                            keys.add(clearKey);
                            break;
                        default:
                            // Пустые места
                            KeyboardKey emptyKey = new KeyboardKey("", "", bounds);
                            keys.add(emptyKey);
                            break;
                    }
                }
            }
        }
    }

    /**
     * Обновление состояния клавиатуры на основе взгляда
     */
    public void updateKeyboard(GazeData gazeData) {
        if (!keyboardVisible || gazeData == null) return;

        double gazeX = gazeData.getCombinedGaze().getX();
        double gazeY = gazeData.getCombinedGaze().getY();

        // Конвертируем нормализованные координаты в экранные
        double screenX = (gazeX + 1) / 2 * 800; // Ширина области клавиатуры
        double screenY = (gazeY + 1) / 2 * 600; // Высота области

        // Проверяем наведение на клавиши
        KeyboardKey hoverKey = null;
        for (KeyboardKey key : keys) {
            if (key.containsPoint(screenX, screenY)) {
                hoverKey = key;
                break;
            }
        }

        long currentTime = System.currentTimeMillis();

        // Обработка наведения
        if (hoverKey != null) {
            if (hoverKey != currentHoverKey) {
                // Новая клавиша
                currentHoverKey = hoverKey;
                hoverStartTime = currentTime;
                hoverKey.setHoverProgress(0);
            } else {
                // Продолжаем наведение
                long hoverTime = currentTime - hoverStartTime;
                long requiredTime = hoverKey.getAction() != null &&
                        hoverKey.getAction().equals("BACKSPACE") ?
                        BACKSPACE_HOVER : HOVER_DURATION;

                double progress = Math.min(1.0, (double) hoverTime / requiredTime);
                hoverKey.setHoverProgress(progress);

                // Если навели достаточно долго
                if (hoverTime >= requiredTime) {
                    processKeyPress(hoverKey);
                    hoverStartTime = currentTime; // Сброс таймера
                }
            }
        } else {
            currentHoverKey = null;
        }

        // Отрисовка обновленной клавиатуры
        drawKeyboard();
    }

    /**
     * Обработка нажатия клавиши
     */
    private void processKeyPress(KeyboardKey key) {
        if (key.isSpecial()) {
            switch (key.getAction()) {
                case "SHIFT":
                    shiftPressed = !shiftPressed;
                    logger.debug("Shift toggled: {}", shiftPressed);
                    break;
                case "SPACE":
                    currentText.append(" ");
                    if (robot != null) {
                        robot.keyPress(java.awt.event.KeyEvent.VK_SPACE);
                        robot.keyRelease(java.awt.event.KeyEvent.VK_SPACE);
                    }
                    break;
                case "ENTER":
                    currentText.append("\n");
                    if (robot != null) {
                        robot.keyPress(java.awt.event.KeyEvent.VK_ENTER);
                        robot.keyRelease(java.awt.event.KeyEvent.VK_ENTER);
                    }
                    break;
                case "BACKSPACE":
                    if (currentText.length() > 0) {
                        currentText.deleteCharAt(currentText.length() - 1);
                    }
                    if (robot != null) {
                        robot.keyPress(java.awt.event.KeyEvent.VK_BACK_SPACE);
                        robot.keyRelease(java.awt.event.KeyEvent.VK_BACK_SPACE);
                    }
                    break;
                case "CLEAR":
                    currentText.setLength(0);
                    break;
            }
        } else {
            // Обычная клавиша
            String ch = key.getChar(shiftPressed);
            currentText.append(ch);

            // Эмуляция нажатия клавиши
            if (robot != null && ch.length() > 0) {
                int keyCode = getKeyCodeForChar(ch.charAt(0));
                if (keyCode != -1) {
                    if (shiftPressed) {
                        robot.keyPress(java.awt.event.KeyEvent.VK_SHIFT);
                    }
                    robot.keyPress(keyCode);
                    robot.keyRelease(keyCode);
                    if (shiftPressed) {
                        robot.keyRelease(java.awt.event.KeyEvent.VK_SHIFT);
                    }
                }
            }

            // Автоматически сбрасываем Shift после нажатия (как на реальной клавиатуре)
            if (shiftPressed && !ch.equals(ch.toUpperCase())) {
                shiftPressed = false;
            }
        }

        // Визуальная обратная связь
        key.setPressed(true);
        new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException e) {}
            key.setPressed(false);
        }).start();

        logger.debug("Key pressed: {}, Text: {}",
                key.getChar(shiftPressed), currentText.toString());
    }

    /**
     * Получение кода клавиши для Robot
     */
    private int getKeyCodeForChar(char c) {
        switch (Character.toLowerCase(c)) {
            case 'a': return java.awt.event.KeyEvent.VK_A;
            case 'b': return java.awt.event.KeyEvent.VK_B;
            case 'c': return java.awt.event.KeyEvent.VK_C;
            case 'd': return java.awt.event.KeyEvent.VK_D;
            case 'e': return java.awt.event.KeyEvent.VK_E;
            case 'f': return java.awt.event.KeyEvent.VK_F;
            case 'g': return java.awt.event.KeyEvent.VK_G;
            case 'h': return java.awt.event.KeyEvent.VK_H;
            case 'i': return java.awt.event.KeyEvent.VK_I;
            case 'j': return java.awt.event.KeyEvent.VK_J;
            case 'k': return java.awt.event.KeyEvent.VK_K;
            case 'l': return java.awt.event.KeyEvent.VK_L;
            case 'm': return java.awt.event.KeyEvent.VK_M;
            case 'n': return java.awt.event.KeyEvent.VK_N;
            case 'o': return java.awt.event.KeyEvent.VK_O;
            case 'p': return java.awt.event.KeyEvent.VK_P;
            case 'q': return java.awt.event.KeyEvent.VK_Q;
            case 'r': return java.awt.event.KeyEvent.VK_R;
            case 's': return java.awt.event.KeyEvent.VK_S;
            case 't': return java.awt.event.KeyEvent.VK_T;
            case 'u': return java.awt.event.KeyEvent.VK_U;
            case 'v': return java.awt.event.KeyEvent.VK_V;
            case 'w': return java.awt.event.KeyEvent.VK_W;
            case 'x': return java.awt.event.KeyEvent.VK_X;
            case 'y': return java.awt.event.KeyEvent.VK_Y;
            case 'z': return java.awt.event.KeyEvent.VK_Z;
            case ' ': return java.awt.event.KeyEvent.VK_SPACE;
            case ',': return java.awt.event.KeyEvent.VK_COMMA;
            case '.': return java.awt.event.KeyEvent.VK_PERIOD;
            case ';': return java.awt.event.KeyEvent.VK_SEMICOLON;
            case '\'': return java.awt.event.KeyEvent.VK_QUOTE;
            case '[': return java.awt.event.KeyEvent.VK_OPEN_BRACKET;
            case ']': return java.awt.event.KeyEvent.VK_CLOSE_BRACKET;
            case '\\': return java.awt.event.KeyEvent.VK_BACK_SLASH;
            case '/': return java.awt.event.KeyEvent.VK_SLASH;
            default: return -1;
        }
    }

    /**
     * Отрисовка клавиатуры на Canvas
     */
    public void drawKeyboard() {
        if (keyboardCanvas == null || !keyboardVisible) return;

        GraphicsContext gc = keyboardCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, keyboardCanvas.getWidth(), keyboardCanvas.getHeight());

        for (KeyboardKey key : keys) {
            Rectangle2D bounds = key.getBounds();

            // Цвет клавиши
            if (key.isPressed()) {
                gc.setFill(Color.LIGHTGREEN);
            } else if (key == currentHoverKey) {
                // Градиент для прогресса наведения
                double progress = key.getHoverProgress();
                Color color = Color.rgb(100, 200, (int)(255 * progress));
                gc.setFill(color);
            } else if (key.isSpecial() && key.getAction() != null) {
                if (key.getAction().equals("SHIFT") && shiftPressed) {
                    gc.setFill(Color.YELLOW);
                } else {
                    gc.setFill(Color.LIGHTGRAY);
                }
            } else {
                gc.setFill(Color.WHITE);
            }

            // Рисуем клавишу
            gc.fillRoundRect(bounds.getMinX(), bounds.getMinY(),
                    bounds.getWidth(), bounds.getHeight(), 10, 10);
            gc.setStroke(Color.BLACK);
            gc.strokeRoundRect(bounds.getMinX(), bounds.getMinY(),
                    bounds.getWidth(), bounds.getHeight(), 10, 10);

            // Рисуем текст
            gc.setFill(Color.BLACK);
            gc.setFont(Font.font(16));

            String text;
            if (key.isSpecial()) {
                text = key.getAction().substring(0, Math.min(3, key.getAction().length()));
            } else {
                text = key.getChar(shiftPressed);
            }

            gc.fillText(text,
                    bounds.getMinX() + bounds.getWidth()/2 - 10,
                    bounds.getMinY() + bounds.getHeight()/2 + 6);

            // Рисуем прогресс-бар для наведения
            if (key == currentHoverKey && key.getHoverProgress() > 0) {
                gc.setFill(Color.rgb(0, 255, 0, 0.3));
                gc.fillRect(bounds.getMinX(), bounds.getMinY() + bounds.getHeight() - 5,
                        bounds.getWidth() * key.getHoverProgress(), 5);
            }
        }
    }

    /**
     * Получить текущий набранный текст
     */
    public String getCurrentText() {
        return currentText.toString();
    }

    /**
     * Очистить текст
     */
    public void clearText() {
        currentText.setLength(0);
    }

    /**
     * Скопировать текст в буфер обмена
     */
    public void copyToClipboard() {
        StringSelection selection = new StringSelection(currentText.toString());
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(selection, selection);
        logger.info("Text copied to clipboard: {}", currentText.toString());
    }

    /**
     * Показать/скрыть клавиатуру
     */
    public void setKeyboardVisible(boolean visible, Canvas canvas) {
        this.keyboardVisible = visible;
        this.keyboardCanvas = canvas;
        if (visible) {
            drawKeyboard();
        }
    }

    public boolean isKeyboardVisible() { return keyboardVisible; }
}