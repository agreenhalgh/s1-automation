package com.newbrid.epr_automation.pdsreview.domain;

/**
 * The two things a terminal decision can lead to. Separated from {@link Decision} so that the
 * question "does this register a patient?" has one answer in one place, and so the gateway route
 * can branch on it without enumerating reason codes it might not yet know about (FR-DEC-05a).
 */
public enum DecisionClass {

    /** A patient is registered on the EPR. */
    REGISTERING,

    /** No patient is registered. The item is closed and nothing is dispatched. */
    CLOSING
}
