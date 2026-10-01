package com.newbrid.epr_automation.pdsreview.review;

import com.newbrid.epr_automation.pdsreview.audit.AuditEvent;
import com.newbrid.epr_automation.pdsreview.audit.AuditService;
import com.newbrid.epr_automation.pdsreview.auth.CurrentReviewer;
import com.newbrid.epr_automation.pdsreview.auth.ReviewerIdentity;
import com.newbrid.epr_automation.pdsreview.config.PdsReviewProperties;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItem;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItemRepository;
import com.newbrid.epr_automation.pdsreview.domain.ReviewSession;
import com.newbrid.epr_automation.pdsreview.domain.ReviewSessionRepository;
import com.newbrid.epr_automation.pdsreview.domain.SearchRecord;
import com.newbrid.epr_automation.pdsreview.domain.SearchRecordRepository;
import com.newbrid.epr_automation.pdsreview.presentation.DataItemMapping;
import com.newbrid.epr_automation.pdsreview.presentation.PresentationView;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The life of one review: claiming an item, looking at it, and putting it back.
 *
 * <p>The claim is the part that matters. Two reviewers holding the same item is how one patient
 * becomes two records, so it is settled with a pessimistic read rather than an optimistic retry
 * (FR-REV-01) — the row is held for microseconds and being certain is worth more than the
 * contention. The lock then expires on its own (FR-REV-02), because the failure mode of a
 * permanent lock is a patient stranded behind somebody's closed laptop.
 */
@Service
public class ReviewService {

    private static final JsonMapper JSON = new JsonMapper();

    /** What happened when a reviewer tried to open an item. */
    public enum ClaimOutcome { CLAIMED, ALREADY_HELD, ALREADY_DECIDED, NOT_FOUND }

    public record ClaimResult(ClaimOutcome outcome, ReviewItem item, String heldBy) {}

    private final ReviewItemRepository items;
    private final ReviewSessionRepository sessions;
    private final SearchRecordRepository searches;
    private final DataItemMapping mapping;
    private final CurrentReviewer currentReviewer;
    private final AuditService audit;
    private final PdsReviewProperties properties;
    private final Clock clock;

    public ReviewService(ReviewItemRepository items, ReviewSessionRepository sessions,
                         SearchRecordRepository searches, DataItemMapping mapping,
                         CurrentReviewer currentReviewer, AuditService audit,
                         PdsReviewProperties properties, Clock clock) {
        this.items = items;
        this.sessions = sessions;
        this.searches = searches;
        this.mapping = mapping;
        this.currentReviewer = currentReviewer;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Takes the item for this reviewer, or says who has it.
     *
     * <p>Re-claiming an item you already hold extends your own lock rather than being refused —
     * a reviewer who reloads the page has not lost their place.
     */
    @Transactional
    public ClaimResult claim(Long itemId) {
        ReviewerIdentity reviewer = currentReviewer.require();
        Instant now = Instant.now(clock);

        Optional<ReviewItem> found = items.findByIdForUpdate(itemId);
        if (found.isEmpty()) {
            return new ClaimResult(ClaimOutcome.NOT_FOUND, null, null);
        }
        ReviewItem item = found.get();

        if (item.decided()) {
            return new ClaimResult(ClaimOutcome.ALREADY_DECIDED, item, null);
        }
        if (item.heldBySomeoneElse(reviewer.userId(), now)) {
            return new ClaimResult(ClaimOutcome.ALREADY_HELD, item, item.getLockedBy());
        }

        boolean alreadyMine = reviewer.userId().equals(item.getLockedBy());
        item.heldBy(reviewer.userId(), reviewer.urid(),
                now.plus(properties.review().lockDuration()));

        if (!alreadyMine) {
            sessions.save(ReviewSession.opened(item.getId(), reviewer.userId(),
                    reviewer.urid(), now));
            audit.record(AuditEvent.ITEM_CLAIMED, item.getItemKey(), item.getCorrelationId(), null,
                    "Held until " + item.getLockedUntil());
        }
        return new ClaimResult(ClaimOutcome.CLAIMED, item, reviewer.userId());
    }

    /**
     * Everything the review screen shows. Reading it is a disclosure of patient data, so it is
     * audited here rather than in the controller — a second entry point that forgot to audit
     * would be a silent gap in the trail (FR-REV-08).
     */
    @Transactional
    public ReviewView open(ReviewItem item) {
        PresentationView presentation = new PresentationView(JSON.readTree(item.getPresentationJson()));
        DataItemMapping.Derived derived = mapping.derive(presentation.answers());

        audit.record(AuditEvent.ITEM_VIEWED, item.getItemKey(), item.getCorrelationId(), null,
                "Opened for review; unmatched reason " + item.getUnmatchedReason());

        return new ReviewView(item, presentation, derived,
                sessions.findByItemIdOrderByStartedAtDesc(item.getId()),
                searches.findByItemIdOrderByRunAtAsc(item.getId()),
                items.earlierDecisionsFor(item.getStatedName(), item.getStatedDateOfBirth(),
                        item.getId(), PageRequest.of(0, 5)));
    }

    /**
     * Discloses the triage pass. Its own audit event because it is bulk patient data on screen,
     * and "who saw the PDF" is a question an auditor may well be asked (FR-REV-06, FR-AUD-01).
     */
    @Transactional
    public Optional<String> disclosePdf(ReviewItem item) {
        PresentationView presentation = new PresentationView(JSON.readTree(item.getPresentationJson()));
        Optional<String> pdf = presentation.pdfBase64();
        if (pdf.isPresent()) {
            audit.record(AuditEvent.PDF_VIEWED, item.getItemKey(), item.getCorrelationId(), null,
                    "Triage-pass PDF disclosed");
        }
        return pdf;
    }

    /**
     * Puts the item back, with a reason, and without touching its priority — a patient's urgency
     * is not reduced by having been looked at (FR-REV-04). The session is kept, so the next
     * reviewer sees what this one did (FR-REV-05).
     */
    @Transactional
    public void returnToQueue(Long itemId, String reason) {
        ReviewerIdentity reviewer = currentReviewer.require();
        Instant now = Instant.now(clock);

        ReviewItem item = items.findByIdForUpdate(itemId).orElseThrow();
        if (item.decided()) {
            return;
        }
        item.returnToQueue();

        sessions.findFirstByItemIdAndEndedAtIsNullOrderByStartedAtDesc(itemId)
                .ifPresent(session -> session.close(ReviewSession.Ending.RETURNED_TO_QUEUE, reason, now));

        audit.record(AuditEvent.ITEM_RETURNED_TO_QUEUE, item.getItemKey(), item.getCorrelationId(),
                null, "Returned to the queue by " + reviewer.userId() + ": " + reason);
    }

    /**
     * Reclaims locks nobody came back to (FR-REV-02). Audited as its own event: an item that
     * silently reappeared in the queue would look, to an auditor, like a reviewer who abandoned
     * it without saying so.
     */
    @Transactional
    public int reclaimExpiredLocks() {
        Instant now = Instant.now(clock);
        List<ReviewItem> expired = items.expiredLocks(now);
        for (ReviewItem item : expired) {
            String previousHolder = item.getLockedBy();
            item.returnToQueue();
            sessions.findFirstByItemIdAndEndedAtIsNullOrderByStartedAtDesc(item.getId())
                    .ifPresent(session -> session.close(ReviewSession.Ending.LOCK_EXPIRED,
                            "The reviewer did not return", now));
            audit.recordSystem(AuditService.SYSTEM_ACTOR_SCHEDULER, AuditEvent.LOCK_EXPIRED,
                    item.getItemKey(), item.getCorrelationId(),
                    "Lock held by " + previousHolder + " expired; back in the queue");
        }
        return expired.size();
    }

    /** The searches run against this item, oldest first — the path the reviewer took. */
    @Transactional(readOnly = true)
    public List<SearchRecord> searchHistory(Long itemId) {
        return searches.findByItemIdOrderByRunAtAsc(itemId);
    }

    public PresentationView presentationOf(ReviewItem item) {
        return new PresentationView(JSON.readTree(item.getPresentationJson()));
    }

    public DataItemMapping.Derived derivedFor(ReviewItem item) {
        return mapping.derive(presentationOf(item).answers());
    }
}
