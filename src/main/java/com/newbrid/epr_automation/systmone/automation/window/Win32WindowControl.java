package com.newbrid.epr_automation.systmone.automation.window;

import com.newbrid.epr_automation.systmone.sdk.engine.WindowActivationException;
import com.newbrid.epr_automation.systmone.sdk.engine.WindowControl;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.WinUser.WINDOWPLACEMENT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link WindowControl} over the Win32 window manager (user32).
 *
 * <h4>Why activation is more than one call</h4>
 * {@code SetForegroundWindow} is deliberately restricted: Windows refuses it from a
 * process that does not already own the foreground, so that background programs cannot
 * steal focus mid-keystroke. A worker driving SystmOne is exactly such a background
 * process. The documented way through is to attach this thread's input queue to the
 * current foreground window's thread, which puts the two in the same input state and
 * lifts the restriction, then detach again. Even then the shell applies the change
 * asynchronously, so the result is <em>verified</em> against {@code GetForegroundWindow}
 * rather than taken from the return code, which lies often enough to be useless alone.
 */
public class Win32WindowControl implements WindowControl {

    private static final Logger log = LoggerFactory.getLogger(Win32WindowControl.class);

    /** How long to wait for the shell to apply the Z-order change before giving up. */
    private static final long VERIFY_TIMEOUT_MS = 2_000;
    private static final long VERIFY_POLL_MS = 25;

    /** A matched top-level window. */
    private record Win(HWND hwnd, String title) {}

    /** Used when a step names no window of its own; typically the SystmOne main window. */
    private final String defaultWindowTitle;

    public Win32WindowControl(String defaultWindowTitle) {
        this.defaultWindowTitle = defaultWindowTitle;
    }

    @Override
    public boolean isAvailable() {
        return Platform.isWindows();
    }

    @Override
    public List<String> titlesMatching(String titleContains) {
        if (!isAvailable()) return List.of();
        return find(resolveTitle(titleContains)).stream().map(Win::title).toList();
    }

    @Override
    public boolean isForeground(String titleContains) {
        if (!isAvailable()) return false;
        HWND fg = User32.INSTANCE.GetForegroundWindow();
        if (fg == null) return false;
        return find(resolveTitle(titleContains)).stream().anyMatch(w -> fg.equals(w.hwnd()));
    }

    /** A step may name its own window (e.g. a dialog); otherwise use the configured one. */
    private String resolveTitle(String titleContains) {
        String requested = titleContains == null ? "" : titleContains.trim();
        if (!requested.isEmpty()) return requested;
        return defaultWindowTitle == null ? "" : defaultWindowTitle.trim();
    }

    @Override
    public String activate(String titleContains) {
        if (!isAvailable()) {
            throw new WindowActivationException(
                    "Window activation requires Windows; this host is " + System.getProperty("os.name"));
        }
        String needle = resolveTitle(titleContains);
        if (needle.isEmpty()) {
            throw new WindowActivationException(
                    "No window title to activate: set the step inputValue, or configure "
                            + "systmone.worker.window-title");
        }

        List<Win> matches = find(needle);
        if (matches.isEmpty()) {
            throw new WindowActivationException(
                    "No visible top-level window has a title containing " + quote(needle));
        }
        if (matches.size() > 1) {
            // Fail-safe: activating the wrong one silently redirects every later keystroke.
            throw new WindowActivationException(
                    "Ambiguous window title " + quote(needle) + " matched " + matches.size()
                            + " windows " + matches.stream().map(Win::title).toList()
                            + " - refusing to guess; narrow the title");
        }

        Win target = matches.get(0);
        if (target.hwnd().equals(User32.INSTANCE.GetForegroundWindow())) {
            return target.title();   // already in front, nothing to do
        }

        restoreIfMinimised(target.hwnd());
        if (raise(target.hwnd()) || raiseWithAttachedInput(target.hwnd())) {
            return target.title();
        }
        throw new WindowActivationException(
                "Windows refused to bring " + quote(target.title()) + " to the foreground within "
                        + VERIFY_TIMEOUT_MS + "ms (foreground is now " + quote(foregroundTitle()) + "). "
                        + "A modal dialog, a locked screen or an elevated window can block this.");
    }

    // =====================================================================
    // internals
    // =====================================================================

    private List<Win> find(String titleContains) {
        String needle = titleContains == null ? "" : titleContains.toLowerCase();
        List<Win> result = new ArrayList<>();
        try {
            User32.INSTANCE.EnumWindows((HWND hwnd, com.sun.jna.Pointer data) -> {
                // Invisible windows cannot take the foreground, and every process owns
                // several hidden message-only windows that would otherwise look ambiguous.
                if (!User32.INSTANCE.IsWindowVisible(hwnd)) return true;
                char[] buf = new char[512];
                User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
                String title = Native.toString(buf);
                if (title != null && !title.isBlank() && title.toLowerCase().contains(needle)) {
                    result.add(new Win(hwnd, title));
                }
                return true;
            }, null);
        } catch (Throwable t) {
            throw new WindowActivationException("Window enumeration failed: " + t);
        }
        return result;
    }

    /** SW_RESTORE un-maximises a maximised window, so only apply it when truly minimised. */
    private void restoreIfMinimised(HWND hwnd) {
        try {
            WINDOWPLACEMENT placement = new WINDOWPLACEMENT();
            placement.length = placement.size();
            if (User32.INSTANCE.GetWindowPlacement(hwnd, placement).booleanValue()
                    && placement.showCmd == WinUser.SW_SHOWMINIMIZED) {
                User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE);
            }
        } catch (Throwable t) {
            log.debug("Could not read window placement, continuing without restore: {}", t.toString());
        }
    }

    private boolean raise(HWND hwnd) {
        User32.INSTANCE.BringWindowToTop(hwnd);
        User32.INSTANCE.SetForegroundWindow(hwnd);
        return waitForForeground(hwnd);
    }

    /**
     * Retry with this thread's input queue attached to the foreground window's thread,
     * which is what lifts the foreground lock for a background process.
     */
    private boolean raiseWithAttachedInput(HWND hwnd) {
        HWND foreground = User32.INSTANCE.GetForegroundWindow();
        if (foreground == null) return false;

        DWORD self = new DWORD(Kernel32.INSTANCE.GetCurrentThreadId());
        DWORD owner = new DWORD(User32.INSTANCE.GetWindowThreadProcessId(foreground, null));
        if (owner.intValue() == 0 || owner.equals(self)) return false;

        boolean attached = false;
        try {
            attached = User32.INSTANCE.AttachThreadInput(self, owner, true);
            if (!attached) log.debug("AttachThreadInput refused; trying to raise anyway");
            return raise(hwnd);
        } finally {
            if (attached) User32.INSTANCE.AttachThreadInput(self, owner, false);
        }
    }

    /** The shell applies the change asynchronously, so poll rather than trust the return code. */
    private boolean waitForForeground(HWND hwnd) {
        long deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS;
        while (true) {
            if (hwnd.equals(User32.INSTANCE.GetForegroundWindow())) return true;
            if (System.currentTimeMillis() >= deadline) return false;
            try {
                Thread.sleep(VERIFY_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private String foregroundTitle() {
        try {
            HWND fg = User32.INSTANCE.GetForegroundWindow();
            if (fg == null) return "none";
            char[] buf = new char[512];
            User32.INSTANCE.GetWindowText(fg, buf, buf.length);
            String title = Native.toString(buf);
            return title == null || title.isBlank() ? "untitled" : title;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String quote(String s) {
        return "'" + s + "'";
    }
}
