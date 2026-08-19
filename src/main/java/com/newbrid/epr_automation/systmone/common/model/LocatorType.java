package com.newbrid.epr_automation.systmone.common.model;

/** The two locator strategies. JAB is preferred; IMAGE is the fallback (§2.2). */
public enum LocatorType {
    /** Java Access Bridge accessibility-tree locator. */
    JAB,
    /** Screen-recognition template (partial screenshot) locator. */
    IMAGE
}
