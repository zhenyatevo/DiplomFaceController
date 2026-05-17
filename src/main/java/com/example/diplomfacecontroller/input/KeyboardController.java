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
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class KeyboardController {

    private static final Logger logger = LoggerFactory.getLogger(KeyboardController.class);

    public enum OutputMode {
        INTERNAL_FIELD,
        SYSTEM_INJECT
    }

    private final List<KeyboardKey> keys = new ArrayList<>();
    private final StringBuilder internalText = new StringBuilder();
    private Consumer<String> textChangeListener;

    private boolean shiftPressed = false;
    private boolean capsLock     = false;
    private KeyboardKey hoverKey = null;
    private long hoverEnterTime  = 0;
    private boolean lastBrowsRaised = false;
    private long lastActivationTime = 0;
    private static final long KEY_COOLDOWN_MS = 1200;
    private static final long MIN_FIXATION_MS = 0;

    private Canvas canvas;
    private boolean visible = false;

    private OutputMode outputMode = OutputMode.INTERNAL_FIELD;
    private final SystemTextInjector systemInjector = new SystemTextInjector();

    /**
     * Поставщик HWND целевого окна (того, куда нужно печатать).
     * Заполняется из {@link com.example.diplomfacecontroller.MainController}:
     * там каждый кадр запоминаем foreground HWND, когда он не равен нашему.
     * Если supplier не выставлен или возвращает null — injectChar уйдёт на
     * Robot-fallback (менее надёжно, но хоть что-то).
     */
    private java.util.function.Supplier<com.sun.jna.platform.win32.WinDef.HWND> targetHwndSupplier;
    public void setTargetHwndSupplier(
            java.util.function.Supplier<com.sun.jna.platform.win32.WinDef.HWND> s) {
        this.targetHwndSupplier = s;
    }

    private Stage ownerStage;
    public void setOwnerStage(Stage stage) { this.ownerStage = stage; }

    private double keyW   = 48;
    private double keyH   = 44;
    private double pad    = 5;
    private double startY = 28;

    public KeyboardController() {}

    public void setTextChangeListener(Consumer<String> listener) {
        this.textChangeListener = listener;
    }

    public void setOutputMode(OutputMode mode) {
        this.outputMode = mode;
        logger.info("Keyboard output mode: {}", mode);
    }

    public OutputMode getOutputMode() { return outputMode; }

    public boolean isSystemInjectAvailable() { return systemInjector.isAvailable(); }

    private void layoutKeys() {
        keys.clear();
        if (canvas == null) return;
        String[][] rowsLower = {
                {"\u0451","1","2","3","4","5","6","7","8","9","0","-","=","BACKSPACE"},
                {"TAB","\u0439","\u0446","\u0443","\u043a","\u0435","\u043d","\u0433","\u0448","\u0449","\u0437","\u0445","\u044a"},
                {"CAPS","\u0444","\u044b","\u0432","\u0430","\u043f","\u0440","\u043e","\u043b","\u0434","\u0436","\u044d","ENTER"},
                {"SHIFT","\u044f","\u0447","\u0441","\u043c","\u0438","\u0442","\u044c","\u0431","\u044e",".","UP"},
                {"ESC","SPACE","LEFT","DOWN","RIGHT","CLEAR"}
        };
        String[][] rowsUpper = {
                {"\u0401","!","\"","\u2116",";","%",":","-","*","(",")","+","=","BACKSPACE"},
                {"TAB","\u0419","\u0426","\u0423","\u041a","\u0415","\u041d","\u0413","\u0428","\u0429","\u0417","\u0425","\u042a"},
                {"CAPS","\u0424","\u042b","\u0412","\u0410","\u041f","\u0420","\u041e","\u041b","\u0414","\u0416","\u042d","ENTER"},
                {"SHIFT","\u042f","\u0427","\u0421","\u041c","\u0418","\u0422","\u042c","\u0411","\u042e",",","UP"},
                {"ESC","SPACE","LEFT","DOWN","RIGHT","CLEAR"}
        };
        double cw = canvas.getWidth();
        double ch = canvas.getHeight();
        keyW = Math.max(36, (cw - pad * 15) / 14.0);
        double statusH = 22;
        keyH = Math.max(30, (ch - statusH - pad * 4) / 5.0);
        startY = (int) statusH;
        for (int r = 0; r < rowsLower.length; r++) {
            String[] lower = rowsLower[r], upper = rowsUpper[r];
            double y = startY + r * (keyH + pad);
            double rowW = 0;
            for (String s : lower) rowW += keyWidth(s) + pad;
            rowW -= pad;
            double x = (cw - rowW) / 2.0;
            for (int c = 0; c < lower.length; c++) {
                String lo = lower[c], up = upper[c];
                Rectangle2D bounds = new Rectangle2D(x, y, keyWidth(lo), keyH);
                if (isSpecialAction(lo)) keys.add(new KeyboardKey(lo, bounds));
                else keys.add(new KeyboardKey(lo, up, bounds));
                x += keyWidth(lo) + pad;
            }
        }
    }

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

    public void update(GazeData gazeData) {
        if (!visible || canvas == null) return;
        if (keys.isEmpty()) layoutKeys();
        Point cursorOnCanvas = getCursorOnCanvas();
        if (cursorOnCanvas == null) {
            hoverKey = null;
            drawKeyboard();
            return;
        }
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
            double progress = (MIN_FIXATION_MS > 0)
                    ? Math.min(1.0, (double)(now - hoverEnterTime) / MIN_FIXATION_MS) : 1.0;
            hoverKey.setHoverProgress(progress);
        }
        if (gazeData != null) lastBrowsRaised = gazeData.isBrowsRaised();
        if (gazeData != null && gazeData.isBrowTriggerEvent() && hoverKey != null) {
            if (now - lastActivationTime >= KEY_COOLDOWN_MS) {
                activateKey(hoverKey);
                lastActivationTime = now;
                hoverEnterTime = now;
            }
        }
        drawKeyboard();
    }

    private Point getCursorOnCanvas() {
        try {
            PointerInfo pi = MouseInfo.getPointerInfo();
            if (pi == null) return null;
            Point screen = pi.getLocation();
            javafx.geometry.Point2D local = canvas.screenToLocal(screen.x, screen.y);
            if (local == null) return null;
            if (local.getX() < 0 || local.getY() < 0
                    || local.getX() > canvas.getWidth() || local.getY() > canvas.getHeight())
                return null;
            return new Point((int) local.getX(), (int) local.getY());
        } catch (Exception e) { return null; }
    }

    private void activateKey(KeyboardKey key) {
        key.setPressed(true);
        new Thread(() -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            Platform.runLater(() -> { key.setPressed(false); drawKeyboard(); });
        }, "KeyVisualReset").start();
        if (key.isSpecial()) {
            handleSpecial(key.getAction());
        } else {
            String ch = key.getChar(effectiveUpperCase());
            appendChar(ch);
            if (shiftPressed) shiftPressed = false;
        }
        logger.info("Key activated: {}", key.isSpecial() ? key.getAction() : key.getChar(effectiveUpperCase()));
    }

    private boolean effectiveUpperCase() { return shiftPressed ^ capsLock; }

    /** Текущий целевой HWND, или null если поставщик не настроен. */
    private com.sun.jna.platform.win32.WinDef.HWND currentTarget() {
        return targetHwndSupplier == null ? null : targetHwndSupplier.get();
    }

    private void handleSpecial(String action) {
        com.sun.jna.platform.win32.WinDef.HWND target = currentTarget();
        switch (action) {
            case "SHIFT": shiftPressed = !shiftPressed; break;
            case "CAPS":  capsLock = !capsLock; break;
            case "BACKSPACE":
                if (outputMode == OutputMode.INTERNAL_FIELD) {
                    if (internalText.length() > 0) {
                        internalText.deleteCharAt(internalText.length()-1);
                        notifyText();
                    }
                } else {
                    if (!systemInjector.injectBackspace(target)) {
                        systemInjector.pressBackspace();
                    }
                }
                break;
            case "ENTER":
                if (outputMode == OutputMode.INTERNAL_FIELD) {
                    internalText.append('\n');
                    notifyText();
                } else {
                    if (!systemInjector.injectEnter(target)) {
                        systemInjector.pressEnter();
                    }
                }
                break;
            case "TAB":
                if (outputMode == OutputMode.INTERNAL_FIELD) {
                    internalText.append('\t');
                    notifyText();
                } else {
                    if (!systemInjector.injectChar('\t', target)) {
                        systemInjector.pressTab();
                    }
                }
                break;
            case "SPACE": appendChar(" "); break;
            case "ESC":   if (outputMode == OutputMode.SYSTEM_INJECT) systemInjector.pressEsc(); break;
            case "CLEAR":
                if (outputMode == OutputMode.INTERNAL_FIELD) { internalText.setLength(0); notifyText(); }
                break;
            case "UP":    if (outputMode == OutputMode.SYSTEM_INJECT) systemInjector.injectArrow(target, 0); break;
            case "DOWN":  if (outputMode == OutputMode.SYSTEM_INJECT) systemInjector.injectArrow(target, 1); break;
            case "LEFT":  if (outputMode == OutputMode.SYSTEM_INJECT) systemInjector.injectArrow(target, 2); break;
            case "RIGHT": if (outputMode == OutputMode.SYSTEM_INJECT) systemInjector.injectArrow(target, 3); break;
        }
    }

    private void appendChar(String ch) {
        if (ch == null || ch.isEmpty()) return;
        if (outputMode == OutputMode.INTERNAL_FIELD) {
            internalText.append(ch);
            notifyText();
            return;
        }
        // SYSTEM_INJECT: PostMessage(WM_CHAR) сразу на запомненное HWND.
        // Без фонового потока — PostMessage возвращается мгновенно, он
        // только кладёт сообщение в очередь окна, не блокируется на
        // обработке.
        com.sun.jna.platform.win32.WinDef.HWND target = currentTarget();
        for (int i = 0; i < ch.length(); i++) {
            char c = ch.charAt(i);
            boolean ok = systemInjector.injectChar(c, target);
            logger.info("Inject char='{}' (U+{}) target={} ok={}",
                    c, Integer.toHexString(c),
                    target == null ? "null" : "HWND",
                    ok);
            // Метрика: каждое срабатывание инжекта — отдельное событие.
            com.example.diplomfacecontroller.utils.StatsLoggerUtils.event(
                    ok ? "key_inject_ok" : "key_inject_failed",
                    String.valueOf(c));
        }
    }

    private void notifyText() {
        if (textChangeListener != null) {
            String snapshot = internalText.toString();
            Platform.runLater(() -> textChangeListener.accept(snapshot));
        }
    }

    public void drawKeyboard() {
        if (canvas == null || !visible) return;
        if (keys.isEmpty()) layoutKeys();
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double cw = canvas.getWidth(), ch = canvas.getHeight();
        gc.setFill(Color.rgb(30, 30, 40));
        gc.fillRect(0, 0, cw, ch);
        boolean upper = effectiveUpperCase();
        String modeStr = (outputMode == OutputMode.SYSTEM_INJECT)
                ? "ВЫВОД: в активное окно ОС" : "ВЫВОД: в поле приложения";
        String browStr = lastBrowsRaised ? "  \u25b2\u0411\u0420\u041e\u0412\u0418\u25b2" : "";
        String hoverStr = (hoverKey != null)
                ? "  |  [" + (hoverKey.isSpecial() ? hoverKey.getAction() : hoverKey.getChar(upper)) + "]"
                : "  |  \u043d\u0430\u0432\u0435\u0434\u0438\u0442\u0435 \u0432\u0437\u0433\u043b\u044f\u0434 \u043d\u0430 \u043a\u043b\u0430\u0432\u0438\u0448\u0443";
        gc.setFill(lastBrowsRaised ? Color.rgb(180,60,20) : Color.rgb(20,90,170));
        gc.fillRoundRect(4, 4, cw-8, 20, 4, 4);
        gc.setFill(Color.WHITE);
        gc.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(modeStr + hoverStr + browStr, 10, 19);
        for (KeyboardKey key : keys) {
            Rectangle2D b = key.getBounds();
            Color fill;
            if (key.isPressed()) fill = Color.rgb(100,220,100);
            else if (key == hoverKey) {
                double p = key.getHoverProgress();
                fill = Color.rgb(60+(int)(40*p), 140+(int)(60*p), 220);
            } else if (key.isSpecial() &&
                    ((key.getAction().equals("SHIFT") && shiftPressed) ||
                            (key.getAction().equals("CAPS")  && capsLock)))
                fill = Color.rgb(255,200,80);
            else if (key.isSpecial()) fill = Color.rgb(80,80,95);
            else fill = Color.rgb(230,230,235);
            gc.setFill(fill);
            gc.fillRoundRect(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight(), 8, 8);
            gc.setStroke(key == hoverKey ? Color.WHITE : Color.rgb(60,60,70));
            gc.setLineWidth(key == hoverKey ? 2.5 : 1.0);
            gc.strokeRoundRect(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight(), 8, 8);
            String label = key.isSpecial() ? specialLabel(key.getAction()) : key.getChar(upper);
            boolean lightBg = !key.isSpecial() && !key.isPressed() && key != hoverKey;
            gc.setFill(lightBg ? Color.rgb(20,20,30) : Color.WHITE);
            double fontSize = key.isSpecial() ? Math.min(14, keyW*0.28) : keyW*0.45;
            gc.setFont(Font.font("Arial", FontWeight.BOLD, fontSize));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(label, b.getMinX()+b.getWidth()/2, b.getMinY()+b.getHeight()/2+fontSize*0.35);
        }
    }

    private String specialLabel(String action) {
        switch (action) {
            case "BACKSPACE": return "\u2190 Bksp";
            case "ENTER":     return "Enter \u21b5";
            case "TAB":       return "Tab \u21e5";
            case "CAPS":      return "Caps";
            case "SHIFT":     return "Shift";
            case "SPACE":     return "Space";
            case "ESC":       return "Esc";
            case "CLEAR":     return "Clear";
            case "UP":        return "\u25b2";
            case "DOWN":      return "\u25bc";
            case "LEFT":      return "\u25c4";
            case "RIGHT":     return "\u25ba";
            default:          return action;
        }
    }

    public void setKeyboardVisible(boolean visible, Canvas canvas) {
        this.visible = visible;
        this.canvas = canvas;
        if (visible && canvas != null) { layoutKeys(); drawKeyboard(); }
    }

    public boolean isKeyboardVisible() { return visible; }
    public String getCurrentText() { return internalText.toString(); }

    public void clearText() { internalText.setLength(0); notifyText(); }

    public void copyToClipboard() {
        StringSelection sel = new StringSelection(internalText.toString());
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        cb.setContents(sel, sel);
        logger.info("Text copied to clipboard ({} chars)", internalText.length());
    }
}