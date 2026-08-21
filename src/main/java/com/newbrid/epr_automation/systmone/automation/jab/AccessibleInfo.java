package com.newbrid.epr_automation.systmone.automation.jab;

/**
 * A snapshot of one accessible node's attributes (FR-JAB-02), decoded from the native
 * {@code AccessibleContextInfo} structure.
 *
 * @param states space/comma-separated state names (enabled, focused, selected, visible, checked, …)
 */
public record AccessibleInfo(
        String role,
        String name,
        String description,
        String states,
        int x, int y, int width, int height,
        int childCount,
        int indexInParent
) {
    /**
     * Whether the state set contains this state.
     *
     * <p>Compared token by token rather than as a substring. Java's state names are a closed set
     * with no negative forms — there is no "invisible", only the absence of "visible" — but a
     * substring test would still say a component reporting "multiselectable" is "selectable",
     * and the traversal now prunes on the answer.</p>
     */
    public boolean hasState(String state) {
        if (states == null || state == null) return false;
        for (String token : states.split("[,;\\s]+")) {
            if (token.equalsIgnoreCase(state.trim())) return true;
        }
        return false;
    }

    /** Whether the bridge told us anything about this node's state at all. */
    public boolean reportsStates() {
        return states != null && !states.isBlank();
    }

    /**
     * Whether this node is on the active UI: {@code showing} if the application reports it,
     * {@code visible} otherwise.
     *
     * <p>The two differ — {@code visible} means the component was set visible, {@code showing}
     * means it and every ancestor are visible and on screen — so {@code showing} is the stronger
     * claim and the one worth trusting. Either is accepted because not every toolkit reports
     * both, and a node that reports only {@code visible} is still the best evidence available.</p>
     */
    public boolean isShowing() {
        return hasState("showing") || hasState("visible");
    }

    /**
     * Whether this node's subtree can be skipped: it says what its state is, and that state is
     * not on screen.
     *
     * <p>Deliberately conditional on {@link #reportsStates()}. Absence of {@code showing} is only
     * evidence of being hidden if the node reports states at all — otherwise a bridge that tells
     * us nothing would look exactly like a window with nothing in it, and every locator in the
     * application would stop resolving. Silence means "carry on as before".</p>
     */
    public boolean hidesSubtree() {
        return reportsStates() && !isShowing();
    }

    public java.awt.Rectangle bounds() {
        return new java.awt.Rectangle(x, y, width, height);
    }
}
