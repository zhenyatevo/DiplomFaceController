package com.example.diplomfacecontroller.input;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.KeyEvent;

/**
 * Эмуляция системной клавиатуры через java.awt.Robot.
 * Используется в режиме "печатать в активное окно ОС".
 *
 * Все методы потокобезопасны — Robot можно дёргать из любого потока,
 * но удобнее всего вызывать из JavaFX Application Thread (там же,
 * где обрабатывается триггер по бровям).
 *
 * ВАЖНО: для корректной работы пользователь должен сначала кликнуть
 * (или вернуть фокус каким-то иным способом) в целевое окно — Notepad,
 * браузер и т.д. Окно с клавиатурой должно быть `setAlwaysOnTop(true)`,
 * но НЕ перехватывать фокус.
 */
public class SystemTextInjector {

    private static final Logger logger = LoggerFactory.getLogger(SystemTextInjector.class);

    private Robot robot;

    public SystemTextInjector() {
        try {
            robot = new Robot();
            robot.setAutoDelay(15); // мини-задержка между нажатиями для надёжности
        } catch (AWTException e) {
            logger.error("Failed to create Robot: {}", e.getMessage());
            robot = null;
        }
    }

    public boolean isAvailable() {
        return robot != null;
    }

    /**
     * Печатает один символ. Для символов, требующих Shift (заглавные буквы,
     * !@#$ и т.д.) — автоматически зажимает Shift.
     */
    public void typeChar(char ch) {
        if (robot == null) return;

        // Для печатных символов используем стратегию:
        // - буквы a-z: VK_A..VK_Z с опциональным Shift
        // - цифры 0-9: VK_0..VK_9 с опциональным Shift
        // - спецсимволы: маппинг на VK + Shift
        ShiftKey sk = mapCharToKey(ch);
        if (sk == null) {
            logger.warn("No mapping for char: '{}' (code={})", ch, (int) ch);
            return;
        }

        try {
            if (sk.shift) robot.keyPress(KeyEvent.VK_SHIFT);
            robot.keyPress(sk.keyCode);
            robot.keyRelease(sk.keyCode);
            if (sk.shift) robot.keyRelease(KeyEvent.VK_SHIFT);
        } catch (Exception e) {
            logger.error("Error typing char '{}': {}", ch, e.getMessage());
        }
    }

    /** Backspace */
    public void pressBackspace() {
        pressKey(KeyEvent.VK_BACK_SPACE);
    }

    /** Enter */
    public void pressEnter() {
        pressKey(KeyEvent.VK_ENTER);
    }

    /** Tab */
    public void pressTab() {
        pressKey(KeyEvent.VK_TAB);
    }

    /** Caps Lock — toggle */
    public void pressCapsLock() {
        pressKey(KeyEvent.VK_CAPS_LOCK);
    }

    public void pressArrowUp()    { pressKey(KeyEvent.VK_UP); }
    public void pressArrowDown()  { pressKey(KeyEvent.VK_DOWN); }
    public void pressArrowLeft()  { pressKey(KeyEvent.VK_LEFT); }
    public void pressArrowRight() { pressKey(KeyEvent.VK_RIGHT); }

    public void pressEsc()  { pressKey(KeyEvent.VK_ESCAPE); }
    public void pressHome() { pressKey(KeyEvent.VK_HOME); }
    public void pressEnd()  { pressKey(KeyEvent.VK_END); }

    private void pressKey(int keyCode) {
        if (robot == null) return;
        try {
            robot.keyPress(keyCode);
            robot.keyRelease(keyCode);
        } catch (Exception e) {
            logger.error("Error pressing key {}: {}", keyCode, e.getMessage());
        }
    }

    /** Внутренняя структура: VK-код + нужен ли Shift. */
    private static class ShiftKey {
        final int keyCode;
        final boolean shift;
        ShiftKey(int keyCode, boolean shift) { this.keyCode = keyCode; this.shift = shift; }
    }

    private ShiftKey mapCharToKey(char ch) {
        // Буквы
        if (ch >= 'a' && ch <= 'z') {
            return new ShiftKey(KeyEvent.VK_A + (ch - 'a'), false);
        }
        if (ch >= 'A' && ch <= 'Z') {
            return new ShiftKey(KeyEvent.VK_A + (ch - 'A'), true);
        }
        // Цифры
        if (ch >= '0' && ch <= '9') {
            return new ShiftKey(KeyEvent.VK_0 + (ch - '0'), false);
        }
        // Спецсимволы (US-раскладка)
        switch (ch) {
            case ' ':  return new ShiftKey(KeyEvent.VK_SPACE, false);
            case '!':  return new ShiftKey(KeyEvent.VK_1, true);
            case '@':  return new ShiftKey(KeyEvent.VK_2, true);
            case '#':  return new ShiftKey(KeyEvent.VK_3, true);
            case '$':  return new ShiftKey(KeyEvent.VK_4, true);
            case '%':  return new ShiftKey(KeyEvent.VK_5, true);
            case '^':  return new ShiftKey(KeyEvent.VK_6, true);
            case '&':  return new ShiftKey(KeyEvent.VK_7, true);
            case '*':  return new ShiftKey(KeyEvent.VK_8, true);
            case '(':  return new ShiftKey(KeyEvent.VK_9, true);
            case ')':  return new ShiftKey(KeyEvent.VK_0, true);
            case '-':  return new ShiftKey(KeyEvent.VK_MINUS, false);
            case '_':  return new ShiftKey(KeyEvent.VK_MINUS, true);
            case '=':  return new ShiftKey(KeyEvent.VK_EQUALS, false);
            case '+':  return new ShiftKey(KeyEvent.VK_EQUALS, true);
            case '[':  return new ShiftKey(KeyEvent.VK_OPEN_BRACKET, false);
            case '{':  return new ShiftKey(KeyEvent.VK_OPEN_BRACKET, true);
            case ']':  return new ShiftKey(KeyEvent.VK_CLOSE_BRACKET, false);
            case '}':  return new ShiftKey(KeyEvent.VK_CLOSE_BRACKET, true);
            case '\\': return new ShiftKey(KeyEvent.VK_BACK_SLASH, false);
            case '|':  return new ShiftKey(KeyEvent.VK_BACK_SLASH, true);
            case ';':  return new ShiftKey(KeyEvent.VK_SEMICOLON, false);
            case ':':  return new ShiftKey(KeyEvent.VK_SEMICOLON, true);
            case '\'': return new ShiftKey(KeyEvent.VK_QUOTE, false);
            case '"':  return new ShiftKey(KeyEvent.VK_QUOTE, true);
            case ',':  return new ShiftKey(KeyEvent.VK_COMMA, false);
            case '<':  return new ShiftKey(KeyEvent.VK_COMMA, true);
            case '.':  return new ShiftKey(KeyEvent.VK_PERIOD, false);
            case '>':  return new ShiftKey(KeyEvent.VK_PERIOD, true);
            case '/':  return new ShiftKey(KeyEvent.VK_SLASH, false);
            case '?':  return new ShiftKey(KeyEvent.VK_SLASH, true);
            case '`':  return new ShiftKey(KeyEvent.VK_BACK_QUOTE, false);
            case '~':  return new ShiftKey(KeyEvent.VK_BACK_QUOTE, true);
            default:   return null;
        }
    }
}