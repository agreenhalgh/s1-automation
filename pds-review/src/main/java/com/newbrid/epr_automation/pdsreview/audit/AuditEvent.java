package com.newbrid.epr_automation.pdsreview.audit;

/**
 * Everything the trail records. Deliberately a closed set: an audit trail whose event names are
 * free text cannot be searched by event (FR-AUD-06), and "everything else" is where the
 * interesting disclosure ends up hiding.
 */
public enum AuditEvent {

    // ---- intake ----------------------------------------------------------------------

    /** The gateway delivered an unmatched presentation and it was admitted. */
    PRESENTATION_ADMITTED,

    /** Delivered again, and already held. Recorded so a redelivery storm is visible. */
    PRESENTATION_ALREADY_HELD,

    /** Delivered and refused — not unmatched, or unusable. */
    PRESENTATION_REJECTED,

    // ---- views (FR-AUD-01) -----------------------------------------------------------

    /** The worklist was opened, with the filter and ordering in force. */
    WORKLIST_VIEWED,

    /** One item was opened for review. */
    ITEM_VIEWED,

    /** The triage-pass PDF was disclosed — bulk patient data on screen, so its own event. */
    PDF_VIEWED,

    // ---- searches (FR-AUD-02) --------------------------------------------------------

    /** A search was issued. Carries the exact parameters sent. */
    SEARCH_RUN,

    /** A search was not issued, because its required parameters could not be derived. */
    SEARCH_NOT_ATTEMPTED,

    /** One candidate's full record was retrieved from PDS. */
    CANDIDATE_RETRIEVED,

    // ---- the review session ----------------------------------------------------------

    ITEM_CLAIMED,

    /** Put back with a reason, priority intact. */
    ITEM_RETURNED_TO_QUEUE,

    /** Nobody came back and the lock ran out (FR-REV-02). */
    LOCK_EXPIRED,

    // ---- decisions (FR-AUD-03) -------------------------------------------------------

    /** A terminal decision was recorded. Carries the decision, its class, and its reason. */
    DECISION_RECORDED,

    /** The decision reached the gateway and was accepted. */
    DECISION_ACKNOWLEDGED,

    /** An attempt to deliver it failed. One row per attempt. */
    DECISION_DELIVERY_FAILED,

    /** The retry budget is spent. Somebody has to be told (FR-DEC-08). */
    DECISION_DELIVERY_ABANDONED,

    // ---- the trail itself ------------------------------------------------------------

    /**
     * Somebody read the audit trail. Recorded because the trail discloses which patients were
     * reviewed and by whom, which is exactly the disclosure it exists to record (FR-AUD-07).
     */
    AUDIT_VIEWED,

    /** The trail was exported (FR-AUD-09). */
    AUDIT_EXPORTED,

    // ---- authentication --------------------------------------------------------------

    /** A reviewer selected the national RBAC role they are working under (FR-AUTH-04). */
    ROLE_SELECTED,

    /** Access refused because the selected role lacked the required activity (FR-AUTH-06). */
    ACCESS_REFUSED
}
