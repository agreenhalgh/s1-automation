package com.newbrid.epr_automation.pdsreview.domain;

/**
 * How far a decision has got towards the gateway (FR-DEC-08). Visible rather than internal: a
 * registering decision that was never delivered is worse than one never made, because from here
 * it looks finished.
 */
public enum PostBackState {

    /** Written down, not yet acknowledged. The state every decision starts in (FR-DEC-07). */
    PENDING,

    /** The gateway accepted it. */
    ACKNOWLEDGED,

    /**
     * The retry budget is spent. Never discarded and never silently retried forever — somebody
     * is told (FR-DEC-08, R9).
     */
    FAILED
}
