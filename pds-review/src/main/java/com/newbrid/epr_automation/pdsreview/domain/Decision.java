package com.newbrid.epr_automation.pdsreview.domain;

/**
 * What a reviewer concluded, and — inseparably — what happens next (FR-DEC-01).
 *
 * <p>The two classes are the important part. Three of these register a patient on the EPR; two
 * close the item with nobody registered. That difference is carried by the name itself, so no
 * caller here, no route on the gateway, and nobody reading the audit trail in two years has to
 * infer from surrounding context whether a registration was meant (FR-DEC-01a).
 *
 * <p>Nothing chooses one of these automatically. Every value is a human decision, explicitly
 * confirmed (NFR-SAFE-01, FR-DEC-03).
 */
public enum Decision {

    /** A PDS candidate is this patient. Registers, carrying the NHS number (FR-DEC-04). */
    MATCHED(DecisionClass.REGISTERING),

    /**
     * Accept the submitted details as given, because the patient is not registered on PDS.
     * Distinct from {@link #ACCEPTED_PATIENT_UNAVAILABLE} because this one asserts something
     * about PDS, and is the only one that should ever be read as "this person has no NHS record"
     * (FR-DEC-02).
     */
    ACCEPTED_NOT_ON_PDS(DecisionClass.REGISTERING),

    /**
     * Accept the submitted details as given, because the patient is not available to confirm
     * them. Says nothing about whether they are on PDS.
     */
    ACCEPTED_PATIENT_UNAVAILABLE(DecisionClass.REGISTERING),

    /**
     * Registration is not necessary: the patient has already been registered on the EPR by hand.
     * Carries where that landed, when the reviewer knows it (FR-DEC-01b).
     */
    CLOSED_REGISTERED_MANUALLY(DecisionClass.CLOSING),

    /** Registration is not necessary: the patient no longer requires it. */
    CLOSED_NO_LONGER_REQUIRED(DecisionClass.CLOSING);

    private final DecisionClass decisionClass;

    Decision(DecisionClass decisionClass) {
        this.decisionClass = decisionClass;
    }

    public DecisionClass decisionClass() {
        return decisionClass;
    }

    /** Whether recording this decision leads to a patient being registered on the EPR. */
    public boolean registers() {
        return decisionClass == DecisionClass.REGISTERING;
    }

    /** Whether this decision closes the item without registering anybody. */
    public boolean closes() {
        return decisionClass == DecisionClass.CLOSING;
    }

    /**
     * Whether a free-text note is required. Every closure needs one: "no longer requires
     * registration" with no reason is a closure nobody can review afterwards — the patient left,
     * they were seen elsewhere, they died, or it was a duplicate presentation, and those are not
     * the same event (FR-DEC-01c).
     */
    public boolean requiresNote() {
        return closes();
    }

    /**
     * Whether the reviewer is asked where the patient was already registered. An assertion that
     * a patient is on the EPR is only checkable if it says where (FR-DEC-01b).
     */
    public boolean requiresExistingRecordPrompt() {
        return this == CLOSED_REGISTERED_MANUALLY;
    }

    /**
     * Whether this decision has to record what PDS was actually asked. With the search order no
     * longer enforced (FR-REV-09), "the reviewer must have searched" cannot be inferred from
     * their having reached the decision screen, so an accept-unmatched decision states it
     * (FR-DEC-01d).
     */
    public boolean requiresSearchEvidence() {
        return this == ACCEPTED_NOT_ON_PDS || this == ACCEPTED_PATIENT_UNAVAILABLE;
    }
}
