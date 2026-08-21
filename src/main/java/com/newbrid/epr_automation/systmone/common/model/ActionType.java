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
    /**
     * How many elements the locator matches, recorded as the step's output.
     *
     * <p>The one action for which several matches are the answer rather than a problem, so it
     * does not resolve an element at all — it never reaches the ambiguity fail-safe that would
     * otherwise refuse to act. Counting matches of a locator rather than "rows of a table"
     * generalises for free: rows are {@code role: table row} inside the grid, columns are the
     * cells of row 0, and the same action counts search results or open tabs.</p>
     *
     * <p>Zero is an answer, not a failure: the step succeeds recording "0". To branch on a grid
     * being empty, use a {@code WAIT} with {@code onMissing} instead.</p>
     *
     * <p>Counts what the accessibility tree currently exposes. A virtualised grid only
     * materialises the rows it is showing, so this is "rows on screen", not "rows in the model" —
     * the latter needs the Access Bridge table API, which this build does not bind.</p>
     */
    GET_COUNT,
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
