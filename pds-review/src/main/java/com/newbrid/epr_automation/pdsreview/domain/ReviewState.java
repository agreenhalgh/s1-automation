package com.newbrid.epr_automation.pdsreview.domain;

/**
 * Where a review item is. Three states and no fourth: an admitted item is either waiting, held by
 * a reviewer, or decided (NFR-REL-02). There is deliberately no "closed but not decided" or
 * "abandoned" — an item nobody picks up stays visibly in the queue rather than quietly leaving it.
 */
public enum ReviewState {

    /** In the worklist, nobody holding it. */
    WAITING,

    /** Held by one reviewer under an unexpired lock (FR-REV-01). */
    IN_REVIEW,

    /** A terminal decision is recorded. Off the worklist, and not re-decidable (FR-DEC-09). */
    DECIDED
}
