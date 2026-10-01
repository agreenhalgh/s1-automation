package com.newbrid.epr_automation.pdsreview.domain;

/**
 * What the waiting time is measured from (FR-WL-03). Recorded per item and shown on the row,
 * because "waiting 40 minutes" means something different depending on which clock started.
 */
public enum WaitBasis {

    /** From {@code user.endTimestamp} — when the patient finished triage. The real clock. */
    TRIAGE_END,

    /** From when the gateway told us. The fallback, when the presentation carried no end time. */
    RECEIPT
}
