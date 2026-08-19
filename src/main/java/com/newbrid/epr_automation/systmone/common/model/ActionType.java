package com.newbrid.epr_automation.systmone.common.model;

/**
 * The set of interactions a step can perform against a resolved element
 * (FR-JAB-04, FR-SR-06). Not every backend supports every action; a backend
 * throws {@code UnsupportedOperationException} for actions it cannot perform,
 * which the engine treats as a step failure (fail-safe, NFR-REL-04).
 */
public enum ActionType {
    CLICK,
    DOUBLE_CLICK,
    RIGHT_CLICK,
    HOVER,
    FOCUS,
    SET_TEXT,
    GET_TEXT,
    SELECT,
    EXPAND,
    COLLAPSE,
    READ_STATE,
    KEYS,
    DRAG,
    /**
     * Bring the target application window to the foreground so later keystrokes and
     * clicks land in it. Acts on the window rather than on an element, so it is the one
     * action that needs no locator chain — the engine performs it through its
     * {@code WindowControl}, not through a backend. {@code inputValue} optionally names
     * the window (default: the worker's {@code systmone.worker.window-title}); a chain,
     * if given, runs first purely as a gate. Records the activated title as its output.
     */
    ACTIVATE,
    /** Resolve the locator and assert it exists/visible, but do not act on it. */
    WAIT
}
