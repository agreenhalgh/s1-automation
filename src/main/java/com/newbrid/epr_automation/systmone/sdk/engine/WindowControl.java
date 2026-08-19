package com.newbrid.epr_automation.systmone.sdk.engine;

import java.util.List;

/**
 * Control over top-level application windows — the port the engine calls for
 * {@code ActionType.ACTIVATE}, alongside {@link ScreenshotSink}.
 *
 * <p>Activation deliberately sits outside {@code AutomationBackend}. A backend locates
 * and drives an <em>element</em>; bringing a window to the front involves no element, is
 * the same operation whichever strategy the rest of the bot uses, and must keep working
 * on a host where neither backend is usable — the Access Bridge disabled, or no display
 * for screen recognition. Routing it through a backend would make the one step that
 * repairs a broken environment depend on that environment being intact.</p>
 *
 * <p>It matters because {@code KEYS} and {@code SET_TEXT} ultimately go to whichever
 * window owns the keyboard focus: a bot that types before the application is in front
 * types into someone else's window.</p>
 *
 * <p>Implementations must be <b>fail-safe</b> (NFR-REL-04): if the title matches more
 * than one window, or the window cannot be verified as foreground afterwards, throw
 * {@link WindowActivationException} rather than report a success the caller cannot
 * trust — typing into the wrong window is exactly the class of error this framework
 * must not commit in a clinical system.</p>
 */
public interface WindowControl {

    /** Whether window control can operate on this host (Windows only). */
    boolean isAvailable();

    /**
     * Bring a visible top-level window to the foreground, restoring it first if it is
     * minimised, and confirm it really is the foreground window before returning.
     *
     * @param titleContains case-insensitive substring of the window title; {@code null}
     *                      or blank means the implementation's configured default
     *                      application window
     * @return the full title of the window that was activated
     * @throws WindowActivationException if no window matches, several match, or the
     *         window could not be verified as foreground
     */
    String activate(String titleContains);

    /** Titles of the visible top-level windows matching {@code titleContains}; for diagnostics. */
    List<String> titlesMatching(String titleContains);

    /** Whether a window matching {@code titleContains} already owns the foreground. */
    boolean isForeground(String titleContains);

    /**
     * Default for an engine built without window control. Fails an ACTIVATE step rather
     * than letting the run type into whatever window happens to be in front.
     */
    WindowControl UNAVAILABLE = new WindowControl() {
        @Override public boolean isAvailable() { return false; }

        @Override public String activate(String titleContains) {
            throw new WindowActivationException(
                    "This engine has no WindowControl configured, so it cannot activate "
                            + (titleContains == null || titleContains.isBlank()
                            ? "the application window" : "'" + titleContains + "'"));
        }

        @Override public List<String> titlesMatching(String titleContains) { return List.of(); }
        @Override public boolean isForeground(String titleContains) { return false; }
    };
}
