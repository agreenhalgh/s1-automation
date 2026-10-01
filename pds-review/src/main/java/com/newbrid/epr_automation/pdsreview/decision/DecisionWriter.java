package com.newbrid.epr_automation.pdsreview.decision;

import com.newbrid.epr_automation.pdsreview.audit.AuditEvent;
import com.newbrid.epr_automation.pdsreview.audit.AuditService;
import com.newbrid.epr_automation.pdsreview.auth.CurrentReviewer;
import com.newbrid.epr_automation.pdsreview.auth.ReviewerIdentity;
import com.newbrid.epr_automation.pdsreview.domain.Decision;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItem;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItemRepository;
import com.newbrid.epr_automation.pdsreview.domain.ReviewSession;
import com.newbrid.epr_automation.pdsreview.domain.ReviewSessionRepository;
import com.newbrid.epr_automation.pdsreview.pds.PdsCandidate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Commits a decision and queues it for delivery, in one transaction.
 *
 * <p>Its own bean because Spring does not intercept a class calling its own
 * {@code @Transactional} method — {@link DecisionService} would have run this with no transaction
 * at all, and silently. Here the boundary is real, which matters more than usual: the item's
 * terminal state, the closed review session, the audit entry and the pending post-back either all
 * exist or none of them do (FR-DEC-07).
 */
@Service
public class DecisionWriter {

    private final ReviewItemRepository items;
    private final ReviewSessionRepository sessions;
    private final CurrentReviewer currentReviewer;
    private final AuditService audit;
    private final Clock clock;

    public DecisionWriter(ReviewItemRepository items, ReviewSessionRepository sessions,
                          CurrentReviewer currentReviewer, AuditService audit, Clock clock) {
        this.items = items;
        this.sessions = sessions;
        this.currentReviewer = currentReviewer;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public DecisionService.Result commit(Long itemId, DecisionRequest request,
                                         PdsCandidate candidate) {
        ReviewerIdentity reviewer = currentReviewer.require();
        Instant now = Instant.now(clock);

        ReviewItem item = items.findByIdForUpdate(itemId).orElse(null);
        if (item == null) {
            return DecisionService.Result.refused("That review item no longer exists");
        }
        if (item.decided()) {
            // Two tabs, or a double submit. The first decision stands: a decision is not
            // re-made, and correcting one means re-admitting the item so both records survive
            // (FR-DEC-09, FR-INT-08).
            return DecisionService.Result.refused("This item has already been decided. To correct "
                    + "a decision the item must be re-admitted, which leaves both records standing.");
        }
        if (item.heldBySomeoneElse(reviewer.userId(), now)) {
            return DecisionService.Result.refused("Another reviewer is holding this item");
        }

        Decision decision = request.decision();
        item.decide(decision, reviewer.userId(), reviewer.urid(), now,
                request.note(), request.existingRecordId(),
                candidate == null ? null : candidate.nhsNumber(),
                candidate == null ? null : candidate.raw());

        sessions.findFirstByItemIdAndEndedAtIsNullOrderByStartedAtDesc(itemId)
                .ifPresent(session -> session.close(ReviewSession.Ending.DECIDED,
                        decision.name(), now));

        audit.record(AuditEvent.DECISION_RECORDED, item.getItemKey(), item.getCorrelationId(),
                item.getMatchedNhsNumber(), detail(decision, request, candidate));

        return DecisionService.Result.ok();
    }

    /**
     * What the trail records about a decision (FR-AUD-03). Names the class as well as the reason
     * code, so one line tells an auditor whether a patient was registered.
     */
    private static String detail(Decision decision, DecisionRequest request, PdsCandidate candidate) {
        StringBuilder detail = new StringBuilder(decision.name())
                .append(" (").append(decision.decisionClass()).append(")")
                .append(decision.registers()
                        ? "; a patient will be registered"
                        : "; no patient will be registered");
        if (candidate != null) {
            detail.append("; matched ").append(candidate.nhsNumber())
                    .append(" score ").append(candidate.score())
                    .append(" version ").append(candidate.versionId());
        }
        if (request.existingRecordId() != null && !request.existingRecordId().isBlank()) {
            detail.append("; already registered as ").append(request.existingRecordId());
        }
        if (request.note() != null && !request.note().isBlank()) {
            detail.append("; note: ").append(request.note());
        }
        return detail.toString();
    }
}
