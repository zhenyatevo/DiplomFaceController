package com.example.diplomfacecontroller.input;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.KeyEvent;

/**
 * Эмуляция системной клавиатуры — отправка символа в чужое окно ОС.
 *
 * <p>Три уровня деградации:
 * <ol>
 *   <li><b>PostMessage(hwnd, WM_CHAR, ch, 0)</b> — основной путь. Отправляет
 *       символ напрямую в HWND, не трогая фокус, не зависит от раскладки
 *       клавиатуры, работает для Unicode (кириллица и т.д.). Поддерживается
 *       всеми приложениями, которые используют стандартные text controls
 *       (браузеры, Notepad, Word, IntelliJ, VS Code). Не работает в играх
 *       и некоторых Electron-приложениях, которые читают raw keyboard input.</li>
 *   <li><b>Robot + clipboard + Ctrl+V</b> — fallback. Используется только
 *       если HWND неизвестен. Менее надёжно: зависит от активного окна
 *       в момент keyPress и от состояния clipboard.</li>
 * </ol>
 *
 * <p>Главный публичный метод — {@link #injectChar(char, WinDef.HWND)}.
 * Он сам выбирает путь и возвращает признак успеха.
 */
public class SystemTextInjector {

    private static final Logger logger = LoggerFactory.getLogger(SystemTextInjector.class);

    private static final int WM_CHAR    = 0x0102;
    private static final int WM_KEYDOWN = 0x0100;
    private static final int WM_KEYUP   = 0x0101;

    private final User32 user32;
    private Robot robot;

    public SystemTextInjector() {
        User32 u;
        try {
            u = User32.INSTANCE;
        } catch (Throwable t) {
            logger.error("JNA User32 unavailable: {}", t.getMessage());
            u = null;
        }
        this.user32 = u;

        try {
            robot = new Robot();
            robot.setAutoDelay(15);
        } catch (AWTException e) {
            logger.error("Failed to create Robot: {}", e.getMessage());
            robot = null;
        }
    }

    public boolean isAvailable() {
        return robot != null || user32 != null;
    }

    // ============================================================
    //                  Главный путь: PostMessage(WM_CHAR)
    // ============================================================

    /**
     * Отправить один Unicode-символ в целевое окно через PostMessage(WM_CHAR).
     * Не трогает фокус, не зависит от раскладки, не интерферирует с
     * пользователем, нажимающим клавиши на физической клавиатуре.
     *
     * @param ch     символ (BMP, U+0000…U+FFFF)
     * @param target HWND поля ввода или окна-контейнера; обычно тот HWND,
     *               который был foreground до того, как пользователь
     *               перевёл взгляд на нашу виртуальную клавиатуру
     * @return true если PostMessage вернул успех (Windows положил в очередь)
     */
    public boolean injectChar(char ch, WinDef.HWND target) {
        if (target == null) {
            // Без target смысла нет — фокусной hwnd не запомнили. Сразу fallback.
            return robotFallback(ch);
        }
        if (user32 == null) {
            return robotFallback(ch);
        }
        try {
            // Шлём WM_CHAR на целевое HWND. wParam = код символа, lParam = 1
            // (repeat count). Windows расщепит это на правильные key events
            // внутри клиентского цикла сообщений.
            //
            // NB: User32.PostMessage в JNA объявлен как void — нативный
            // BOOL не возвращается. Считаем успехом отсутствие исключения;
            // если HWND невалиден, Windows просто проигнорирует, символ
            // не появится — но и крэша не будет. Видно по логу
            // (key_inject_ok будет, а символа в окне — нет).
            user32.PostMessage(
                    target,
                    WM_CHAR,
                    new WinDef.WPARAM(ch),
                    new WinDef.LPARAM(1));
            return true;
        } catch (Throwable t) {
            logger.error("injectChar('{}') failed: {}", ch, t.getMessage());
            return robotFallback(ch);
        }
    }

    /**
     * Backspace в целевое окно через PostMessage(WM_KEYDOWN/WM_KEYUP).
     * Аналогично injectChar, не трогает фокус.
     */
    public boolean injectBackspace(WinDef.HWND target) {
        return injectVK(target, 0x08); // VK_BACK
    }

    public boolean injectEnter(WinDef.HWND target) {
        // Для подтверждения формы (поисковая строка, login и т.д.) браузеру
        // нужен именно VK_RETURN через WM_KEYDOWN/WM_KEYUP — он навешивает
        // обработчик на key events, а не на WM_CHAR. Шлём связкой:
        // WM_KEYDOWN(VK_RETURN) → WM_CHAR('\r') → WM_KEYUP(VK_RETURN).
        // Средний WM_CHAR нужен для текстовых полей (textarea), где Enter
        // вставляет перевод строки.
        if (target == null || user32 == null) return false;
        try {
            user32.PostMessage(target, WM_KEYDOWN,
                    new WinDef.WPARAM(0x0D), new WinDef.LPARAM(1));
            user32.PostMessage(target, WM_CHAR,
                    new WinDef.WPARAM(0x0D), new WinDef.LPARAM(1));
            user32.PostMessage(target, WM_KEYUP,
                    new WinDef.WPARAM(0x0D), new WinDef.LPARAM(0xC0000001L));
            return true;
        } catch (Throwable t) {
            logger.error("injectEnter failed: {}", t.getMessage());
            return false;
        }
    }

    public void injectArrow(WinDef.HWND target, int direction) {
        // direction: 0=Up, 1=Down, 2=Left, 3=Right
        int vk;
        switch (direction) {
            case 0: vk = 0x26; break; // VK_UP
            case 1: vk = 0x28; break; // VK_DOWN
            case 2: vk = 0x25; break; // VK_LEFT
            case 3: vk = 0x27; break; // VK_RIGHT
            default: return;
        }
        injectVK(target, vk);
    }

    private boolean injectVK(WinDef.HWND target, int vk) {
        if (target == null || user32 == null) return false;
        try {
            // PostMessage в JNA — void; считаем успехом отсутствие исключения.
            user32.PostMessage(target, WM_KEYDOWN,
                    new WinDef.WPARAM(vk), new WinDef.LPARAM(1));
            user32.PostMessage(target, WM_KEYUP,
                    new WinDef.WPARAM(vk), new WinDef.LPARAM(0xC0000001L));
            return true;
        } catch (Throwable t) {
            logger.error("injectVK({}) failed: {}", vk, t.getMessage());
            return false;
        }
    }

    // ============================================================
    //                  Fallback: Robot + clipboard
    // ============================================================

    /**
     * Если HWND неизвестен — пробуем через Robot и Ctrl+V с clipboard.
     * Это последний рубеж, и он зависит от того, какое окно сейчас активно
     * в Windows. Может промахнуться, если наше JavaFX-окно случайно
     * получит фокус в момент keyPress.
     */
    private boolean robotFallback(char ch) {
        if (robot == null) return false;
        try {
            String s = String.valueOf(ch);
            java.awt.datatransfer.Clipboard cb =
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            java.awt.datatransfer.Transferable saved = null;
            try { saved = cb.getContents(null); } catch (Throwable ignore) {}
            cb.setContents(new java.awt.datatransfer.StringSelection(s), null);
            Thread.sleep(15);
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_CONTROL);
            if (saved != null) {
                final java.awt.datatransfer.Transferable original = saved;
                new Thread(() -> {
                    try { Thread.sleep(200); cb.setContents(original, null); }
                    catch (Throwable ignore) {}
                }, "ClipRestore").start();
            }
            return true;
        } catch (Exception e) {
            logger.error("robotFallback error: {}", e.getMessage());
            return false;
        }
    }

    // ============================================================
    //          Legacy API (fallback в KeyboardController)
    // ============================================================
    // Эти методы идут через Robot и зависят от активного окна ОС.
    // KeyboardController использует их только когда новый inject*(target)
    // вернул false — например, если HWND неизвестен.

    public void pressBackspace() { pressKey(KeyEvent.VK_BACK_SPACE); }
    public void pressEnter()     { pressKey(KeyEvent.VK_ENTER); }
    public void pressTab()       { pressKey(KeyEvent.VK_TAB); }
    public void pressEsc()       { pressKey(KeyEvent.VK_ESCAPE); }

    private void pressKey(int keyCode) {
        if (robot == null) return;
        try {
            robot.keyPress(keyCode);
            robot.keyRelease(keyCode);
        } catch (Exception e) {
            logger.error("Error pressing key {}: {}", keyCode, e.getMessage());
        }
    }
}