package com.newbrid.epr_automation.pdsreview.domain;

/**
 * Which search was run. Recorded per execution so the path a reviewer took through the three
 * searches is evidence rather than an assumption — the order is a default they may depart from
 * (FR-REV-09, FR-REV-10).
 */
public enum SearchKind {

    /** PDS without {@code _fuzzy-match}, run automatically on opening an item (FR-PDS-01). */
    PDS_NON_FUZZY,

    /** PDS narrowed by {@code general-practitioner} (FR-ODS-01). */
    PDS_BY_GP_PRACTICE,

    /** PDS with {@code _fuzzy-match=true} and reviewer-selected parameters (FR-FUZ-01). */
    PDS_FUZZY,

    /** ODS {@code GET /Organization}, to find a practice by name or place (FR-ODS-02). */
    ODS_GP_PRACTICE,

    /** PDS {@code GET /Patient/{nhsNumber}}, retrieving one candidate in full (FR-PDS-11). */
    PDS_RETRIEVE
}
