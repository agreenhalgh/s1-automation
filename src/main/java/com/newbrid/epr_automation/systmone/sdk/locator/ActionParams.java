package com.newbrid.epr_automation.systmone.sdk.locator;

/**
 * Resolved parameters for an action, passed by the engine to a backend.
 *
 * @param text    resolved input value for SET_TEXT / SELECT
 * @param keys    key sequence for KEYS (e.g. {@code "CTRL+S"}, {@code "ENTER"})
 * @param offsetX click-point X offset from the element's centre (image locators); for
 *                DRAG, the X component of the drag delta
 * @param offsetY click-point Y offset from the element's centre; for DRAG, the Y delta
 */
public record ActionParams(String text, String keys, Integer offsetX, Integer offsetY) {

    public static final ActionParams NONE = new ActionParams(null, null, null, null);

    public static ActionParams text(String text) {
        return new ActionParams(text, null, null, null);
    }

    public int offsetXOr(int fallback) {
        return offsetX == null ? fallback : offsetX;
    }

    public int offsetYOr(int fallback) {
        return offsetY == null ? fallback : offsetY;
    }
}
