package com.newbrid.epr_automation.pdsreview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * One unmatched presentation awaiting, or having had, human review.
 *
 * <p><b>The presentation is kept as the JSON it arrived as</b> (FR-INT-04). Re-serialising it
 * through a typed model here would make a change to OPTO Health's Mongoose models break the review
 * queue, and the post-back has to be able to hand the presentation onward unaltered. What is
 * copied out into columns is only what the worklist orders, filters and displays — the alternative
 * is parsing a 60 KB document per row to draw a table (FR-INT-06, NFR-PERF-01).
 *
 * <p><b>Everything here is patient-identifiable.</b> Unlike the gateway, this application cannot
 * pseudonymise: its whole function is to show a clinician a patient's details beside candidate
 * matches. That is why §5.4 of the requirements asks for encryption at rest and a retention
 * policy rather than the hashing the gateway does.
 *
 * <p>The decision and its delivery state live on the item rather than in a separate table. There
 * is at most one decision per item and it cannot be re-made (FR-DEC-09), so the outbox is simply
 * "the items whose post-back is still pending".
 */
@Entity
@Table(name = "review_item", indexes = {
        @Index(name = "idx_review_item_key", columnList = "itemKey", unique = true),
        @Index(name = "idx_review_item_queue", columnList = "state, priorityRank, waitingSince"),
        @Index(name = "idx_review_item_postback", columnList = "postBackState, postBackNextAttemptAt"),
        @Index(name = "idx_review_item_correlation", columnList = "correlationId")
})
public class ReviewItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The stable identity of this presentation, derived from the submission (FR-INT-05). Unique,
     * so a redelivered POST cannot create a second item — the database enforces idempotency
     * rather than a check-then-insert that two concurrent requests would both pass.
     */
    @Column(nullable = false, length = 128)
    private String itemKey;

    /** The gateway's correlation id, so this item joins up with its rows in the gateway's trail. */
    @Column(length = 128)
    private String correlationId;

    private Instant receivedAt;

    /** The presentation, verbatim. */
    @Lob
    @Column(nullable = false)
    private String presentationJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReviewState state;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UnmatchedReason unmatchedReason;

    // ---- what the worklist orders and shows (FR-INT-06, FR-WL-01) --------------------

    /** {@code user.pValue} — P0 (most urgent) to P5. Null when the sender did not score it. */
    @Column(length = 8)
    private String triageScore;

    /**
     * Position under the configured ordering (FR-WL-05). Held as a column so the database can
     * order and page 10,000 items rather than the application loading them to sort. An unscored
     * item gets the band the configuration asks for — by default above P0, because a missing
     * score is not evidence of low urgency (FR-WL-04).
     */
    @Column(nullable = false)
    private int priorityRank;

    private Instant waitingSince;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WaitBasis waitBasis;

    @Column(length = 200)
    private String statedName;

    /** As the patient wrote it. Not a date: an unparseable answer is still what they told us. */
    @Column(length = 40)
    private String statedDateOfBirth;

    @Column(length = 200)
    private String hospitalName;

    @Column(length = 32)
    private String departmentCode;

    @Column(length = 200)
    private String departmentName;

    // ---- the lock (FR-REV-01, FR-REV-02) --------------------------------------------

    @Column(length = 128)
    private String lockedBy;

    @Column(length = 64)
    private String lockedByRole;

    private Instant lockedUntil;

    // ---- the decision (FR-DEC) ------------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private Decision decision;

    private Instant decidedAt;

    @Column(length = 128)
    private String decidedBy;

    @Column(length = 64)
    private String decidedByRole;

    @Column(length = 4000)
    private String decisionNote;

    /** Where the patient was already registered, for CLOSED_REGISTERED_MANUALLY (FR-DEC-01b). */
    @Column(length = 64)
    private String existingRecordId;

    /** The NHS number of the selected candidate, for MATCHED (FR-DEC-04). */
    @Column(length = 20)
    private String matchedNhsNumber;

    /** The matched patient as PDS returned them, added to the presentation on post-back. */
    @Lob
    private String matchedPatientJson;

    // ---- delivery of the decision (FR-DEC-07, FR-DEC-08) ----------------------------

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private PostBackState postBackState;

    private int postBackAttempts;

    private Instant postBackNextAttemptAt;

    @Column(length = 2000)
    private String postBackLastError;

    /** The identifier the control plane gave the registered patient, when it returns one. */
    @Column(length = 64)
    private String mrn;

    @Version
    private Long version;

    protected ReviewItem() {}

    private ReviewItem(String itemKey, String correlationId, String presentationJson,
                       UnmatchedReason unmatchedReason, Instant receivedAt) {
        this.itemKey = itemKey;
        this.correlationId = correlationId;
        this.presentationJson = presentationJson;
        this.unmatchedReason = unmatchedReason;
        this.receivedAt = receivedAt;
        this.state = ReviewState.WAITING;
    }

    /**
     * A newly admitted item. Deliberately takes the queue-ordering fields together with the
     * payload: an item that reached the worklist without a rank or a waiting-since would sort
     * arbitrarily, which for a queue ordered by clinical urgency is not a cosmetic fault.
     */
    public static ReviewItem admit(String itemKey, String correlationId, String presentationJson,
                                   UnmatchedReason unmatchedReason, Instant receivedAt,
                                   String triageScore, int priorityRank,
                                   Instant waitingSince, WaitBasis waitBasis) {
        ReviewItem item = new ReviewItem(itemKey, correlationId, presentationJson,
                unmatchedReason, receivedAt);
        item.triageScore = triageScore;
        item.priorityRank = priorityRank;
        item.waitingSince = waitingSince;
        item.waitBasis = waitBasis;
        return item;
    }

    public void describePatient(String statedName, String statedDateOfBirth) {
        this.statedName = statedName;
        this.statedDateOfBirth = statedDateOfBirth;
    }

    public void describeContext(String hospitalName, String departmentCode, String departmentName) {
        this.hospitalName = hospitalName;
        this.departmentCode = departmentCode;
        this.departmentName = departmentName;
    }

    // ---- lifecycle -------------------------------------------------------------------

    /** Held by this reviewer until {@code until}. Only ever called after the claim has won. */
    public void heldBy(String user, String role, Instant until) {
        this.state = ReviewState.IN_REVIEW;
        this.lockedBy = user;
        this.lockedByRole = role;
        this.lockedUntil = until;
    }

    /**
     * Back in the queue, lock released, priority untouched — a patient's urgency is not reduced
     * by having been looked at (FR-REV-04).
     */
    public void returnToQueue() {
        this.state = ReviewState.WAITING;
        this.lockedBy = null;
        this.lockedByRole = null;
        this.lockedUntil = null;
    }

    /**
     * Records the terminal decision and queues it for delivery. Registering or closing, it is
     * written down here before anything is sent (FR-DEC-07) — a post-back that is not first
     * committed is a decision that can be lost.
     */
    public void decide(Decision decision, String user, String role, Instant when,
                       String note, String existingRecordId,
                       String matchedNhsNumber, String matchedPatientJson) {
        this.decision = decision;
        this.decidedBy = user;
        this.decidedByRole = role;
        this.decidedAt = when;
        this.decisionNote = note;
        this.existingRecordId = existingRecordId;
        this.matchedNhsNumber = matchedNhsNumber;
        this.matchedPatientJson = matchedPatientJson;
        this.state = ReviewState.DECIDED;
        this.lockedBy = null;
        this.lockedByRole = null;
        this.lockedUntil = null;
        this.postBackState = PostBackState.PENDING;
        this.postBackAttempts = 0;
        this.postBackNextAttemptAt = when;
    }

    public void postBackAcknowledged(String mrn) {
        this.postBackState = PostBackState.ACKNOWLEDGED;
        this.postBackLastError = null;
        this.postBackNextAttemptAt = null;
        if (mrn != null && !mrn.isBlank()) {
            this.mrn = mrn;
        }
    }

    /** One failed attempt. Retried until the budget is spent, then reported, never dropped. */
    public void postBackFailed(String error, Instant nextAttemptAt, int maxAttempts) {
        this.postBackAttempts++;
        this.postBackLastError = error;
        if (this.postBackAttempts >= maxAttempts) {
            this.postBackState = PostBackState.FAILED;
            this.postBackNextAttemptAt = null;
        } else {
            this.postBackNextAttemptAt = nextAttemptAt;
        }
    }

    /** Whether this item is currently held by somebody other than {@code user}. */
    public boolean heldBySomeoneElse(String user, Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now)
                && lockedBy != null && !lockedBy.equals(user);
    }

    public boolean decided() {
        return state == ReviewState.DECIDED;
    }

    public Long getId() { return id; }
    public String getItemKey() { return itemKey; }
    public String getCorrelationId() { return correlationId; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getPresentationJson() { return presentationJson; }
    public ReviewState getState() { return state; }
    public UnmatchedReason getUnmatchedReason() { return unmatchedReason; }
    public String getTriageScore() { return triageScore; }
    public int getPriorityRank() { return priorityRank; }
    public Instant getWaitingSince() { return waitingSince; }
    public WaitBasis getWaitBasis() { return waitBasis; }
    public String getStatedName() { return statedName; }
    public String getStatedDateOfBirth() { return statedDateOfBirth; }
    public String getHospitalName() { return hospitalName; }
    public String getDepartmentCode() { return departmentCode; }
    public String getDepartmentName() { return departmentName; }
    public String getLockedBy() { return lockedBy; }
    public String getLockedByRole() { return lockedByRole; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Decision getDecision() { return decision; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecidedBy() { return decidedBy; }
    public String getDecidedByRole() { return decidedByRole; }
    public String getDecisionNote() { return decisionNote; }
    public String getExistingRecordId() { return existingRecordId; }
    public String getMatchedNhsNumber() { return matchedNhsNumber; }
    public String getMatchedPatientJson() { return matchedPatientJson; }
    public PostBackState getPostBackState() { return postBackState; }
    public int getPostBackAttempts() { return postBackAttempts; }
    public Instant getPostBackNextAttemptAt() { return postBackNextAttemptAt; }
    public String getPostBackLastError() { return postBackLastError; }
    public String getMrn() { return mrn; }
}
