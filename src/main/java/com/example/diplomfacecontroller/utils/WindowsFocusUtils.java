package com.example.diplomfacecontroller.utils;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Делает JavaFX-окно "non-activating overlay" в стиле экранных клавиатур
 * и accessibility-инструментов (Tobii, On-Screen Keyboard и т.д.).
 *
 * <p>Связка флагов на Windows:
 * <ul>
 *   <li>{@code WS_EX_NOACTIVATE} — окно не забирает focus у активного
 *       приложения при клике в само окно. Это позволяет печатать в чужое
 *       окно (браузер, Word) через Robot/Ctrl+V, не отбирая у него caret.</li>
 *   <li>{@code WS_EX_TOPMOST} — окно держится поверх остальных, но БЕЗ
 *       активации. Это НЕ то же самое, что {@code Stage.setAlwaysOnTop(true)}
 *       в JavaFX, который на Windows форсит активацию при показе и тем
 *       самым отменяет эффект NOACTIVATE.</li>
 *   <li>{@code WS_EX_APPWINDOW} — заставляет Windows показать окно в
 *       taskbar и Alt+Tab. Без этого флага неактивируемое окно может
 *       пропасть из переключателя задач и стать "потерянным" после
 *       клика в другое приложение.</li>
 * </ul>
 *
 * <p>Метод идемпотентный — повторный вызов безопасен. Вызывать нужно
 * ПОСЛЕ показа окна (из {@code stage.setOnShown}), потому что нативный
 * HWND становится валидным только тогда.
 */
public class WindowsFocusUtils {

    private static final Logger logger = LoggerFactory.getLogger(WindowsFocusUtils.class);

    private static final int GWL_EXSTYLE        = -20;
    private static final int WS_EX_NOACTIVATE   = 0x08000000;
    private static final int WS_EX_TOPMOST      = 0x00000008;
    private static final int WS_EX_APPWINDOW    = 0x00040000;
    // Снимаем флаг, который скрывает окно из taskbar (на случай если он
    // был добавлен где-то ещё либо унаследован от JavaFX дефолтов).
    private static final int WS_EX_TOOLWINDOW   = 0x00000080;

    // SetWindowPos флаги
    private static final int SWP_NOMOVE         = 0x0002;
    private static final int SWP_NOSIZE         = 0x0001;
    private static final int SWP_NOACTIVATE     = 0x0010;
    private static final int SWP_SHOWWINDOW     = 0x0040;

    // HWND_TOPMOST = -1, передаётся как Pointer
    private static final WinDef.HWND HWND_TOPMOST = new WinDef.HWND(new Pointer(-1));

    public static void makeWindowNoActivate(Stage stage) {
        try {
            // 1) Получаем native HWND. У JavaFX 17 это через com.sun.glass.ui.Window.
            //    Берём окно, соответствующее данному Stage, а не просто первое —
            //    если в будущем появится второе Stage (например, диалог), это
            //    защитит от того, что мы случайно ноакативируем чужое окно.
            long hwndVal = 0;
            for (com.sun.glass.ui.Window w : com.sun.glass.ui.Window.getWindows()) {
                // У Stage нет публичного getNativeWindow; полагаемся на то, что
                // в большинстве случаев у нас одно главное окно. Если их несколько,
                // выбираем то, что соответствует stage по координатам.
                if (Math.abs(w.getX() - stage.getX()) < 5 &&
                        Math.abs(w.getY() - stage.getY()) < 5) {
                    hwndVal = w.getNativeWindow();
                    break;
                }
            }
            // Fallback: если по координатам не нашли (например, окно ещё не
            // позиционировано), берём первое.
            if (hwndVal == 0 && !com.sun.glass.ui.Window.getWindows().isEmpty()) {
                hwndVal = com.sun.glass.ui.Window.getWindows().get(0).getNativeWindow();
            }
            if (hwndVal == 0) {
                logger.warn("makeWindowNoActivate: HWND not available, skipping");
                return;
            }

            WinDef.HWND hwnd = new WinDef.HWND(new Pointer(hwndVal));

            // 2) Меняем extended style.
            int exStyle = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE);
            exStyle |=  WS_EX_NOACTIVATE;   // не получать focus при кликах
            exStyle |=  WS_EX_TOPMOST;      // держаться поверх (без активации)
            exStyle |=  WS_EX_APPWINDOW;    // показываться в taskbar / Alt+Tab
            exStyle &= ~WS_EX_TOOLWINDOW;   // не превращаться в tool window
            //   (tool window прячется из taskbar)
            User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, exStyle);

            // 3) Применяем TOPMOST через SetWindowPos с SWP_NOACTIVATE —
            //    без этого вызова смена WS_EX_TOPMOST через SetWindowLong
            //    не вступает в силу. SWP_NOACTIVATE критичен: иначе окно
            //    активируется прямо сейчас и отберёт focus у другого приложения.
            User32.INSTANCE.SetWindowPos(
                    hwnd,
                    HWND_TOPMOST,
                    0, 0, 0, 0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW);

            logger.info("Window configured as non-activating top-most overlay (HWND={})", hwndVal);
        } catch (Throwable t) {
            // Throwable, не Exception: ловим и LinkageError если jna несовместима.
            logger.error("makeWindowNoActivate failed: {}", t.getMessage(), t);
        }
    }

    /**
     * Возвращает HWND нашего главного окна, найденного по координатам Stage.
     * Может быть null, если окно ещё не показано или JavaFX недоступен.
     */
    public static WinDef.HWND getOwnHwnd(Stage stage) {
        try {
            for (com.sun.glass.ui.Window w : com.sun.glass.ui.Window.getWindows()) {
                if (Math.abs(w.getX() - stage.getX()) < 5 &&
                        Math.abs(w.getY() - stage.getY()) < 5) {
                    long h = w.getNativeWindow();
                    if (h != 0) return new WinDef.HWND(new Pointer(h));
                }
            }
            if (!com.sun.glass.ui.Window.getWindows().isEmpty()) {
                long h = com.sun.glass.ui.Window.getWindows().get(0).getNativeWindow();
                if (h != 0) return new WinDef.HWND(new Pointer(h));
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /**
     * Возвращает HWND окна, которое сейчас в foreground у Windows.
     * Может вернуть наше же окно, если у нас фокус. Сравнивать снаружи.
     */
    public static WinDef.HWND getForegroundHwnd() {
        try {
            return User32.INSTANCE.GetForegroundWindow();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Проверка эквивалентности двух HWND по числовому значению.
     */
    public static boolean sameHwnd(WinDef.HWND a, WinDef.HWND b) {
        if (a == null || b == null) return a == b;
        Pointer pa = a.getPointer();
        Pointer pb = b.getPointer();
        long la = pa == null ? 0 : Pointer.nativeValue(pa);
        long lb = pb == null ? 0 : Pointer.nativeValue(pb);
        return la == lb;
    }
}