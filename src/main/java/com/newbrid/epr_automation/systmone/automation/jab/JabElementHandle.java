package com.newbrid.epr_automation.systmone.automation.jab;

import com.newbrid.epr_automation.systmone.sdk.locator.ElementHandle;

import java.awt.Rectangle;

/** {@link ElementHandle} wrapping a resolved accessibility-tree node. */
public record JabElementHandle(Rectangle screenBounds, AcRef ref, String description) implements ElementHandle {
}
