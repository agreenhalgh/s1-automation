package com.newbrid.epr_automation.systmone.sdk.locator;

import java.awt.Rectangle;

/**
 * An opaque handle to a resolved on-screen element, returned by an
 * {@link AutomationBackend}. A JAB handle wraps an accessible-context reference; an
 * image handle wraps the matched rectangle. Both expose screen bounds so the engine
 * and other backends can act on them uniformly (NFR-MNT-01).
 *
 * <p>Closeable because a JAB handle is not a value: it is a 64-bit reference to an object the
 * target JVM has been told to hold on to, and it stays held until the bridge is told to let go.
 * The engine closes a handle once it has acted on it, which is what keeps a long-running worker
 * from pinning every control it has ever looked at inside SystmOne's heap.</p>
 */
public interface ElementHandle extends AutoCloseable {

    /** Bounds of the element in absolute screen coordinates. */
    Rectangle screenBounds();

    /** Short human-readable description for logs and step reports. */
    String description();

    /**
     * Release whatever the backend is holding for this element. Nothing for an image match, which
     * is a rectangle; for JAB, the accessible-context reference. Overridden to declare no checked
     * exception: releasing is best-effort and must not become another thing a step can fail on.
     */
    @Override
    default void close() {}
}
