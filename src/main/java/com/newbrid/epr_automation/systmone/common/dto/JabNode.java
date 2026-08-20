package com.newbrid.epr_automation.systmone.common.dto;

/**
 * One node of a Java application's accessibility tree, flattened for transport to the
 * authoring tool (FR-JAB-02, FR-TOOL-02).
 *
 * <p>The same shape as the worker's internal {@code AccessibleInfo}, deliberately kept
 * separate: this one crosses a wire and is read by a browser, so it is a message contract
 * rather than a native decoding detail.</p>
 *
 * <p>Bounds are screen coordinates in the worker's own logical pixel space — the space
 * {@code java.awt.Robot} captures in, so they can be drawn straight onto a screenshot with
 * no conversion. This is the same assumption the JAB backend already acts on when it falls
 * back to clicking the centre of an element's bounds.</p>
 */
public record JabNode(
        String role,
        String name,
        String description,
        String states,
        int x, int y, int width, int height,
        int indexInParent,
        int childCount
) {
}
