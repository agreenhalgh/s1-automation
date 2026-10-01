package com.newbrid.epr_automation.pdsreview.decision;

import com.newbrid.epr_automation.pdsreview.audit.AuditEvent;
import com.newbrid.epr_automation.pdsreview.audit.AuditService;
import com.newbrid.epr_automation.pdsreview.domain.PostBackState;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItem;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Records what happened to a delivery attempt.
 *
 * <p>Every attempt leaves a row, successful or not (FR-DEC-08). That is what makes the difference
 * between "this decision is taking a while" and "this decision is never going to arrive" visible
 * to a service manager instead of buried in a log — and R9 is that a decided item which was never
 * registered looks finished from in here.
 */
@Service
public class PostBackWriter {

    private final ReviewItemRepository items;
    private final AuditService audit;

    public PostBackWriter(ReviewItemRepository items, AuditService audit) {
        this.items = items;
        this.audit = audit;
    }

    @Transactional
    public void acknowledged(Long itemId, String mrn) {
        ReviewItem item = items.findById(itemId).orElseThrow();
        item.postBackAcknowledged(mrn);
        audit.recordSystem(AuditService.SYSTEM_ACTOR_SCHEDULER, AuditEvent.DECISION_ACKNOWLEDGED,
                item.getItemKey(), item.getCorrelationId(),
                "The gateway accepted the decision after " + (item.getPostBackAttempts() + 1)
                        + " attempt(s)" + (mrn == null ? "" : "; registered as " + mrn));
    }

    /**
     * One failed attempt. Retried until the budget is spent, and then reported rather than
     * dropped — the decision stays on the record, visibly undelivered.
     */
    @Transactional
    public void failed(Long itemId, String reason, Instant nextAttemptAt, int maxAttempts) {
        ReviewItem item = items.findById(itemId).orElseThrow();
        item.postBackFailed(reason, nextAttemptAt, maxAttempts);

        boolean abandoned = item.getPostBackState() == PostBackState.FAILED;
        audit.recordSystem(AuditService.SYSTEM_ACTOR_SCHEDULER,
                abandoned ? AuditEvent.DECISION_DELIVERY_ABANDONED
                        : AuditEvent.DECISION_DELIVERY_FAILED,
                item.getItemKey(), item.getCorrelationId(),
                "Attempt " + item.getPostBackAttempts() + " of " + maxAttempts + " failed: "
                        + reason + (abandoned
                        ? ". No further attempts will be made; this decision has NOT reached the "
                          + "control plane and needs a human."
                        : ""));
    }
}
