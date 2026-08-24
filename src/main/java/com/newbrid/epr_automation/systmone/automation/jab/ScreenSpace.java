package com.newbrid.epr_automation.systmone.automation.jab;

import java.awt.Rectangle;

/**
 * Puts a node's reported bounds into screen coordinates, correcting children that report them
 * relative to their parent instead.
 *
 * <p>Table cells are where this bites. A Swing {@code JTable} exposes each cell through a
 * synthetic accessible object, and those routinely report a rectangle measured from the table's
 * own top-left rather than from the screen. Taken at face value the cell appears to be somewhere
 * near the top of the desktop, so a click inside it looks like a click outside every cell — the
 * capture tool then answers with the table, and the engine would drive the mouse to the wrong
 * place entirely.</p>
 *
 * <p>The correction is evidence-based, not a guess about which toolkit is in use: a child's
 * absolute rectangle must overlap its parent's, because it is drawn inside it. If it does not, and
 * translating it by the parent's origin puts it inside, then it was measured from the parent. Any
 * node already reporting screen coordinates overlaps its parent and is left alone.</p>
 *
 * @param dx accumulated correction, applied to every node reached through this space
 * @param dy the same, vertically
 */
public record ScreenSpace(int dx, int dy) {

    /** The space a top-level window reports in: screen coordinates, uncorrected. */
    public static final ScreenSpace SCREEN = new ScreenSpace(0, 0);

    /** This node's bounds in screen coordinates. */
    public Rectangle absolute(AccessibleInfo info) {
        Rectangle bounds = info.bounds();
        bounds.translate(dx, dy);
        return bounds;
    }

    /**
     * The space a child of {@code parent} reports in.
     *
     * @param parentAbsolute the parent's bounds, already corrected
     * @return this space unchanged when the child agrees with it, or one shifted by the parent's
     *         origin when the child is plainly measuring from there instead
     */
    public ScreenSpace forChild(AccessibleInfo child, Rectangle parentAbsolute) {
        if (child == null || parentAbsolute == null) return this;
        Rectangle asReported = absolute(child);
        // An unbounded child says nothing about its coordinate space, and neither does a parent
        // with no extent to be inside of.
        if (asReported.isEmpty() || parentAbsolute.isEmpty()) return this;
        if (asReported.intersects(parentAbsolute)) return this;

        Rectangle asRelative = child.bounds();
        asRelative.translate(parentAbsolute.x, parentAbsolute.y);
        if (!asRelative.intersects(parentAbsolute)) {
            // Neither reading puts it inside its parent. Something else is going on — an overlay,
            // a popup, a stale rectangle — and inventing a translation would only move the error.
            return this;
        }
        return new ScreenSpace(parentAbsolute.x, parentAbsolute.y);
    }
}
