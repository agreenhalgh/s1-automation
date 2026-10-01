package com.newbrid.epr_automation.pdsreview.domain;

/**
 * Why this presentation is in the queue at all (FR-INT-01). Recorded on admission rather than
 * inferred later, and shown to the reviewer before they start (FR-REV-03).
 *
 * <p>The distinction between {@link #PDS_LOOKUP_UNSUCCESSFUL} and {@link #PDS_UNAVAILABLE} is the
 * one that matters: it changes what a nil return from the reviewer's own search means. Conflating
 * them would make "accepted — not on PDS" a guess about a patient PDS was never asked about (R10).
 */
public enum UnmatchedReason {

    /** {@code user.nhs.status} was {@code unsuccessful}: PDS answered, and matched nobody. */
    PDS_LOOKUP_UNSUCCESSFUL,

    /** {@code user.nhs.status} was {@code unavailable}: PDS was not reachable, or never asked. */
    PDS_UNAVAILABLE,

    /**
     * No demographics and no status either — {@code nhsUser} null and {@code user.nhs} absent
     * from the JSON, which is what every captured sample looks like. Nothing is known about
     * whether PDS was ever consulted, which is weaker than either case above and is why it is
     * not folded into them.
     */
    NOT_STATED
}
