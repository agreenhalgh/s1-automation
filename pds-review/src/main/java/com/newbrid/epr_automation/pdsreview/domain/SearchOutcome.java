package com.newbrid.epr_automation.pdsreview.domain;

/**
 * How a search ended. {@link #NONE}, {@link #TOO_MANY} and {@link #ERROR} are three different
 * answers and are never collapsed: "no matches", "your search was too broad", and "PDS did not
 * answer" lead a reviewer to opposite conclusions, and rendering the last as the first is how an
 * "accepted — not on PDS" decision gets made about a patient PDS was never asked about
 * (FR-PDS-09, FR-PDS-12, R10).
 */
public enum SearchOutcome {

    /** Candidates were returned. */
    RESULTS,

    /** The search ran and matched nobody. */
    NONE,

    /** More matches than PDS will return, so it returned none of them. */
    TOO_MANY,

    /** PDS or ODS did not answer, or refused. Nothing was established either way. */
    ERROR,

    /** Not attempted: the required parameters could not be derived (FR-PDS-04). */
    NOT_ATTEMPTED
}
