package com.newbrid.epr_automation.pdsreview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One reviewer's sitting with one item. Kept after it ends, so an item returned to the queue
 * carries its history to whoever picks it up next and they do not repeat the last person's work
 * (FR-REV-05).
 */
@Entity
@Table(name = "review_session", indexes = {
        @Index(name = "idx_review_session_item", columnList = "itemId, startedAt")
})
public class ReviewSession {

    /** How a sitting ended. Three ways, and "still open" is the absence of all of them. */
    public enum Ending {
        /** The reviewer put it back, with a reason (FR-REV-04). */
        RETURNED_TO_QUEUE,
        /** A terminal decision was recorded. */
        DECIDED,
        /** Nobody came back to it and the lock ran out (FR-REV-02). */
        LOCK_EXPIRED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false, length = 128)
    private String reviewer;

    @Column(length = 64)
    private String reviewerRole;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private Ending ending;

    /** Why it was put back. Required of a return, so the next reviewer learns something. */
    @Column(length = 2000)
    private String reason;

    protected ReviewSession() {}

    public static ReviewSession opened(Long itemId, String reviewer, String reviewerRole, Instant at) {
        ReviewSession session = new ReviewSession();
        session.itemId = itemId;
        session.reviewer = reviewer;
        session.reviewerRole = reviewerRole;
        session.startedAt = at;
        return session;
    }

    public void close(Ending ending, String reason, Instant at) {
        this.ending = ending;
        this.reason = reason;
        this.endedAt = at;
    }

    public boolean open() {
        return endedAt == null;
    }

    public Long getId() { return id; }
    public Long getItemId() { return itemId; }
    public String getReviewer() { return reviewer; }
    public String getReviewerRole() { return reviewerRole; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public Ending getEnding() { return ending; }
    public String getReason() { return reason; }
}
