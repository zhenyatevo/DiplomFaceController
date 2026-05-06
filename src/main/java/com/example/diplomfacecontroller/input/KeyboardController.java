package com.example.diplomfacecontroller.input;

import com.example.diplomfacecontroller.models.GazeData;
import com.example.diplomfacecontroller.models.KeyboardKey;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.stage.Stage;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Виртуальная клавиатура на Canvas, управляемая взглядом + триггером бровями.
 *
 * Основные принципы:
 *  1. Координаты для определения "на какую клавишу смотрит пользователь" берутся
 *     ИЗ ПОЗИЦИИ КУРСОРА МЫШИ. Это работает потому, что у пользователя уже есть
 *     рабочий MouseController, который двигает курсор согласно взгляду.
 *     Не нужно повторно конвертировать gaze[-1,1] в пиксели — это уже сделано.
 *
 *  2. Подтверждение выбора — событие `gazeData.isBrowTriggerEvent()`,
 *     приходящее ровно на одном кадре когда пользователь поднимает брови.
 *
 *  3. Два режима вывода: INTERNAL (свой StringBuilder) и SYSTEM
 *     (через SystemTextInjector → Robot → активное окно ОС).
 */
public class KeyboardController {

    private static final Logger logger = LoggerFactory.getLogger(KeyboardController.class);

    public enum OutputMode {
        /** Печатать в свой StringBuilder, отображаемый в окне приложения. */
        INTERNAL_FIELD,
        /** Печатать в активное окно ОС через java.awt.Robot. */
        SYSTEM_INJECT
    }

    private final List<KeyboardKey> keys = new ArrayList<>();

    /** Внутренний буфер для режима INTERNAL_FIELD. */
    private final StringBuilder internalText = new StringBuilder();

    /** Колбек для обновления UI (поле textOutputLabel) при изменении внутреннего текста. */
    private Consumer<String> textChangeListener;

    private boolean shiftPressed = false;
    private boolean capsLock     = false;

    private KeyboardKey hoverKey = null;
    private long hoverEnterTime  = 0;
    private boolean lastBrowsRaised = false;  // для индикатора в статус-баре
    /** Время последней активации клавиши — защита от двойного срабатывания. */
    private long lastActivationTime = 0;
    private static final long KEY_COOLDOWN_MS = 1200;  // мин. интервал между нажатиями

    /** Минимальное время удержания взгляда на клавише до того, как
     *  триггер по бровям сможет её активировать. Защищает от случайных
     *  активаций при пролёте взгляда мимо. */
    private static final long MIN_FIXATION_MS = 0;  // бровь = мгновенное подтверждение

    private Canvas canvas;
    private boolean visible = false;

    private OutputMode outputMode = OutputMode.INTERNAL_FIELD;
    private final SystemTextInjector systemInjector = new SystemTextInjector();
    private Stage ownerStage;

    public void setOwnerStage(Stage stage) { this.ownerStage = stage; }

    /** Размеры клавиш — могут перевычисляться при смене размера canvas. */
    private double keyW   = 48;
    private double keyH   = 44;
    private double pad    = 5;
    private double startY = 28;  // сдвинуто вниз из-за статус-бара

    public KeyboardController() {
        // Раскладка строится динамически в layoutKeys() при первом drawKeyboard
    }

    public void setTextChangeListener(Consumer<String> listener) {
        this.textChangeListener = listener;
    }

    public void setOutputMode(OutputMode mode) {
        this.outputMode = mode;
        logger.info("Keyboard output mode: {}", mode);
    }

    public OutputMode getOutputMode() {
        return outputMode;
    }

    public boolean isSystemInjectAvailable() {
        return systemInjector.isAvailable();
    }

    /**
     * Строит сетку клавиш под текущий размер canvas.
     * Вызывается при первом показе и при изменении размера.
     */
    private void layoutKeys() {
        keys.clear();
        if (canvas == null) return;

        // Пять рядов:
        // 0: ` 1 2 3 4 5 6 7 8 9 0 - = Backspace
        // 1: Tab q w e r t y u i o p [ ] \
        // 2: Caps a s d f g h j k l ; ' Enter
        // 3: Shift z x c v b n m , . / Up
        // 4: Esc Space Left Down Right
        String[][] rowsLower = {
                {"`","1","2","3","4","5","6","7","8","9","0","-","=","BACKSPACE"},
                {"TAB","q","w","e","r","t","y","u","i","o","p","[","]","\\"},
                {"CAPS","a","s","d","f","g","h","j","k","l",";","'","ENTER"},
                {"SHIFT","z","x","c","v","b","n","m",",",".","/","UP"},
                {"ESC","SPACE","LEFT","DOWN","RIGHT","CLEAR"}
        };
        String[][] rowsUpper = {
                {"~","!","@","#","$","%","^","&","*","(",")","_","+","BACKSPACE"},
                {"TAB","Q","W","E","R","T","Y","U","I","O","P","{","}","|"},
                {"CAPS","A","S","D","F","G","H","J","K","L",":","\"","ENTER"},
                {"SHIFT","Z","X","C","V","B","N","M","<",">","?","UP"},
                {"ESC","SPACE","LEFT","DOWN","RIGHT","CLEAR"}
        };

        // Подгоняем размер клавиши под ширину canvas (14 клавиш в самом длинном ряду)
        double cw = canvas.getWidth();
        double ch = canvas.getHeight();
        // Ширина клавиши по самому широкому ряду (14 клавиш)
        keyW = Math.max(36, (cw - pad * 15) / 14.0);
        // Высота клавиши: подгоняем под высоту canvas (5 рядов)
        double statusH = 22;  // статус-бар сверху
        keyH = Math.max(30, (ch - statusH - pad * 4) / 5.0);
        startY = (int) statusH;

        for (int r = 0; r < rowsLower.length; r++) {
            String[] lower = rowsLower[r];
            String[] upper = rowsUpper[r];
            double y = startY + r * (keyH + pad);

            // Считаем суммарную ширину ряда (с учётом широких клавиш) для центрирования
            double rowW = 0;
            for (String s : lower) {
                rowW += keyWidth(s) + pad;
            }
            rowW -= pad;
            double x = (cw - rowW) / 2.0;

            for (int c = 0; c < lower.length; c++) {
                String lo = lower[c];
                String up = upper[c];
                double w = keyWidth(lo);
                Rectangle2D bounds = new Rectangle2D(x, y, w, keyH);

                if (isSpecialAction(lo)) {
                    keys.add(new KeyboardKey(lo, bounds));
                } else {
                    keys.add(new KeyboardKey(lo, up, bounds));
                }
                x += w + pad;
            }
        }
    }

    /** Возвращает ширину клавиши в пикселях с учётом её типа. */
    private double keyWidth(String label) {
        switch (label) {
            case "BACKSPACE": return keyW * 1.8;
            case "TAB":       return keyW * 1.3;
            case "CAPS":      return keyW * 1.5;
            case "ENTER":     return keyW * 1.7;
            case "SHIFT":     return keyW * 1.7;
            case "SPACE":     return keyW * 6.0;
            case "ESC":       return keyW * 1.3;
            case "CLEAR":     return keyW * 1.3;
            default:          return keyW;
        }
    }

    private boolean isSpecialAction(String label) {
        switch (label) {
            case "BACKSPACE": case "TAB": case "CAPS": case "ENTER":
            case "SHIFT": case "SPACE": case "ESC": case "CLEAR":
            case "UP": case "DOWN": case "LEFT": case "RIGHT":
                return true;
            default:
                return false;
        }
    }

    /**
     * Главный метод: обновляет состояние клавиатуры на каждом кадре трекинга.
     *
     * @param gazeData свежие данные от GazeEstimator (для триггера по бровям)
     */
    public void update(GazeData gazeData) {
        if (!visible || canvas == null) return;
        if (keys.isEmpty()) layoutKeys();

        // Координаты курсора в КООРДИНАТАХ CANVAS
        Point cursorOnCanvas = getCursorOnCanvas();
        if (cursorOnCanvas == null) {
            // Курсор за пределами canvas — снимаем hover, но не теряем состояние
            hoverKey = null;
            drawKeyboard();
            return;
        }

        // Ищем клавишу под курсором
        KeyboardKey newHover = null;
        for (KeyboardKey k : keys) {
            if (k.containsPoint(cursorOnCanvas.x, cursorOnCanvas.y)) {
                newHover = k;
                break;
            }
        }

        long now = System.currentTimeMillis();
        if (newHover != hoverKey) {
            hoverKey = newHover;
            hoverEnterTime = now;
            if (hoverKey != null) hoverKey.setHoverProgress(0);
        } else if (hoverKey != null) {
            // Анимация прогресса фиксации (визуальная, до триггера)
            double progress = (MIN_FIXATION_MS > 0) ? Math.min(1.0, (double)(now - hoverEnterTime) / MIN_FIXATION_MS) : 1.0;
            hoverKey.setHoverProgress(progress);
        }

        // ===== ТРИГГЕР =====
        if (gazeData != null) {
            lastBrowsRaised = gazeData.isBrowsRaised();
        }
        if (gazeData != null && gazeData.isBrowTriggerEvent() && hoverKey != null) {
            // Защита от двойного нажатия: игнорируем если прошло меньше KEY_COOLDOWN_MS
            if (now - lastActivationTime >= KEY_COOLDOWN_MS) {
                activateKey(hoverKey);
                lastActivationTime = now;
                hoverEnterTime = now;
            }
        }

        drawKeyboard();
    }

    /**
     * Конвертирует системные координаты курсора в координаты canvas.
     * Возвращает null если курсор за пределами окна с canvas.
     */
    private Point getCursorOnCanvas() {
        try {
            PointerInfo pi = MouseInfo.getPointerInfo();
            if (pi == null) return null;
            Point screen = pi.getLocation();

            // localToScreen / screenToLocal — конверсия через JavaFX
            javafx.geometry.Point2D local = canvas.screenToLocal(screen.x, screen.y);
            if (local == null) return null;
            if (local.getX() < 0 || local.getY() < 0
                    || local.getX() > canvas.getWidth() || local.getY() > canvas.getHeight()) {
                return null;
            }
            return new Point((int) local.getX(), (int) local.getY());
        } catch (Exception e) {
            return null;
        }
    }

    /** Активация клавиши — основной обработчик нажатия. */
    private void activateKey(KeyboardKey key) {
        key.setPressed(true);
        // При OS-вводе небольшая пауза для передачи фокуса
        if (outputMode == OutputMode.SYSTEM_INJECT) {
            try { Thread.sleep(80); } catch (InterruptedException ignored) {}
        }
        // Снимем "нажатость" через 150 мс (визуальный feedback)
        new Thread(() -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            Platform.runLater(() -> { key.setPressed(false); drawKeyboard(); });
        }, "KeyVisualReset").start();

        if (key.isSpecial()) {
            handleSpecial(key.getAction());
        } else {
            boolean upper = effectiveUpperCase();
            String ch = key.getChar(upper);
            appendChar(ch);
            // Shift одноразовый — после ввода буквы сбрасываем
            if (shiftPressed) {
                shiftPressed = false;
            }
        }
        logger.info("Key activated: {}", key.isSpecial() ? key.getAction() : key.getChar(effectiveUpperCase()));
    }

    private boolean effectiveUpperCase() {
        // Caps XOR Shift — как на настоящей клавиатуре
        return shiftPressed ^ capsLock;
    }

    private void handleSpecial(String action) {
        switch (action) {
            case "SHIFT":
                shiftPressed = !shiftPressed;
                break;
            case "CAPS":
                capsLock = !capsLock;
                break;
            case "BACKSPACE":
                if (outputMode == OutputMode.INTERNAL_FIELD) {
                    if (internalText.length() > 0) {
                        internalText.deleteCharAt(internalText.length() - 1);
                        notifyText();
                    }
                } else {
                    systemInjector.pressBackspace();
                }
                break;
            case "ENTER":
                if (outputMode == OutputMode.INTERNAL_FIELD) {
                    internalText.append('\n');
                    notifyText();
                } else {
                    systemInjector.pressEnter();
                }
                break;
            case "TAB":
                if (outputMode == OutputMode.INTERNAL_FIELD) {
                    internalText.append('\t');
                    notifyText();
                } else {
                    systemInjector.pressTab();
                }
                break;
            case "SPACE":
                appendChar(" ");
                break;
            case "ESC":
                if (outputMode == OutputMode.SYSTEM_INJECT) systemInjector.pressEsc();
                break;
            case "CLEAR":
                if (outputMode == OutputMode.INTERNAL_FIELD) {
                    internalText.setLength(0);
                    notifyText();
                }
                // В системном режиме CLEAR ничего не делает — мы не управляем чужим полем
                break;
            case "UP":    if (outputMode == OutputMode.SYSTEM_INJECT) systemInjector.pressArrowUp();    break;
            case "DOWN":  if (outputMode == OutputMode.SYSTEM_INJECT) systemInjector.pressArrowDown();  break;
            case "LEFT":  if (outputMode == OutputMode.SYSTEM_INJECT) systemInjector.pressArrowLeft();  break;
            case "RIGHT": if (outputMode == OutputMode.SYSTEM_INJECT) systemInjector.pressArrowRight(); break;
        }
    }

    private void appendChar(String ch) {
        if (ch == null || ch.isEmpty()) return;
        if (outputMode == OutputMode.INTERNAL_FIELD) {
            internalText.append(ch);
            notifyText();
        } else {
            systemInjector.typeChar(ch.charAt(0));
        }
    }

    private void notifyText() {
        if (textChangeListener != null) {
            String snapshot = internalText.toString();
            Platform.runLater(() -> textChangeListener.accept(snapshot));
        }
    }

    /** Отрисовка клавиатуры на Canvas. */
    public void drawKeyboard() {
        if (canvas == null || !visible) return;
        if (keys.isEmpty()) layoutKeys();

        GraphicsContext gc = canvas.getGraphicsContext2D();
        double cw = canvas.getWidth();
        double ch = canvas.getHeight();

        // Фон
        gc.setFill(Color.rgb(30, 30, 40));
        gc.fillRect(0, 0, cw, ch);

        boolean upper = effectiveUpperCase();

        // ===== СТАТУС-БАР: куда идёт текст =====
        String modeStr = (outputMode == OutputMode.SYSTEM_INJECT)
                ? "ВЫВОД: в активное окно ОС"
                : "ВЫВОД: в поле приложения";
        String browStr = lastBrowsRaised ? "  ▲БРОВИ▲" : "";
        String hoverStr = (hoverKey != null)
                ? "  |  [" + (hoverKey.isSpecial() ? hoverKey.getAction() : hoverKey.getChar(upper)) + "]"
                : "  |  наведите взгляд на клавишу";
        Color barColor = lastBrowsRaised ? Color.rgb(180, 60, 20) : Color.rgb(20, 90, 170);
        gc.setFill(barColor);
        gc.fillRoundRect(4, 4, cw - 8, 20, 4, 4);
        gc.setFill(Color.WHITE);
        gc.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(modeStr + hoverStr + browStr, 10, 19);

        for (KeyboardKey key : keys) {
            Rectangle2D b = key.getBounds();

            // Цвет фона клавиши
            Color fill;
            if (key.isPressed()) {
                fill = Color.rgb(100, 220, 100);
            } else if (key == hoverKey) {
                // Прогресс fixation: от синего к зелёному
                double p = key.getHoverProgress();
                fill = Color.rgb(60 + (int)(40 * p), 140 + (int)(60 * p), 220);
            } else if (key.isSpecial() &&
                    ((key.getAction().equals("SHIFT") && shiftPressed) ||
                            (key.getAction().equals("CAPS")  && capsLock))) {
                fill = Color.rgb(255, 200, 80);
            } else if (key.isSpecial()) {
                fill = Color.rgb(80, 80, 95);
            } else {
                fill = Color.rgb(230, 230, 235);
            }

            gc.setFill(fill);
            gc.fillRoundRect(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight(), 8, 8);

            // Обводка
            gc.setStroke(key == hoverKey ? Color.WHITE : Color.rgb(60, 60, 70));
            gc.setLineWidth(key == hoverKey ? 2.5 : 1.0);
            gc.strokeRoundRect(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight(), 8, 8);

            // Текст клавиши
            String label;
            if (key.isSpecial()) {
                label = specialLabel(key.getAction());
            } else {
                label = key.getChar(upper);
            }

            // Цвет текста: тёмный на светлых, светлый на тёмных
            boolean lightBg = !key.isSpecial() && !key.isPressed() && key != hoverKey;
            gc.setFill(lightBg ? Color.rgb(20, 20, 30) : Color.WHITE);

            double fontSize = key.isSpecial() ? Math.min(14, keyW * 0.28) : keyW * 0.45;
            gc.setFont(Font.font("Arial", FontWeight.BOLD, fontSize));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(label,
                    b.getMinX() + b.getWidth() / 2,
                    b.getMinY() + b.getHeight() / 2 + fontSize * 0.35);
        }
    }

    private String specialLabel(String action) {
        switch (action) {
            case "BACKSPACE": return "← Bksp";
            case "ENTER":     return "Enter ↵";
            case "TAB":       return "Tab ⇥";
            case "CAPS":      return "Caps";
            case "SHIFT":     return "Shift";
            case "SPACE":     return "Space";
            case "ESC":       return "Esc";
            case "CLEAR":     return "Clear";
            case "UP":        return "▲";
            case "DOWN":      return "▼";
            case "LEFT":      return "◄";
            case "RIGHT":     return "►";
            default:          return action;
        }
    }

    public void setKeyboardVisible(boolean visible, Canvas canvas) {
        this.visible = visible;
        this.canvas = canvas;
        if (visible && canvas != null) {
            layoutKeys();
            drawKeyboard();
        }
    }

    public boolean isKeyboardVisible() { return visible; }

    public String getCurrentText() {
        return internalText.toString();
    }

    public void clearText() {
        internalText.setLength(0);
        notifyText();
    }

    public void copyToClipboard() {
        StringSelection sel = new StringSelection(internalText.toString());
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        cb.setContents(sel, sel);
        logger.info("Text copied to clipboard ({} chars)", internalText.length());
    }
}