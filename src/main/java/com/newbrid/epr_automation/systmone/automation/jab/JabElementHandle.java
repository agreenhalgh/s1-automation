package com.newbrid.epr_automation.systmone.automation.jab;

import com.newbrid.epr_automation.systmone.sdk.locator.ElementHandle;

import java.awt.Rectangle;

/**
 * {@link ElementHandle} wrapping a resolved accessibility-tree node.
 *
 * <p>Carries the bridge so the handle can give the node back. Every {@code AccessibleContext} the
 * bridge hands out is a reference the target JVM holds for us; leaving them to a garbage collector
 * that knows nothing about them is how SystmOne ends up pinning thousands of dead components.</p>
 */
public record JabElementHandle(Rectangle screenBounds, AcRef ref, String description,
                               AccessBridge bridge) implements ElementHandle {

    @Override
    public void close() {
        if (bridge != null) bridge.release(ref);
    }
}
