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
    public boolean hasState(String state) {
        return states != null && states.toLowerCase().contains(state.toLowerCase());
    }

    public java.awt.Rectangle bounds() {
        return new java.awt.Rectangle(x, y, width, height);
    }
}
