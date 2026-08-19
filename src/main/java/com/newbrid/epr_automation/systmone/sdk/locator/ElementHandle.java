package com.newbrid.epr_automation.systmone.sdk.locator;

import java.awt.Rectangle;

/**
 * An opaque handle to a resolved on-screen element, returned by an
 * {@link AutomationBackend}. A JAB handle wraps an accessible-context reference; an
 * image handle wraps the matched rectangle. Both expose screen bounds so the engine
 * and other backends can act on them uniformly (NFR-MNT-01).
 */
public interface ElementHandle {

    /** Bounds of the element in absolute screen coordinates. */
    Rectangle screenBounds();

    /** Short human-readable description for logs and step reports. */
    String description();
}
