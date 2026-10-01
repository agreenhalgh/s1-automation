package com.newbrid.epr_automation.pdsreview.intake;

import com.newbrid.epr_automation.pdsreview.audit.AuditEvent;
import com.newbrid.epr_automation.pdsreview.audit.AuditService;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItem;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItemRepository;
import com.newbrid.epr_automation.pdsreview.presentation.DataItemMapping;
import com.newbrid.epr_automation.pdsreview.presentation.PresentationView;
import com.newbrid.epr_automation.pdsreview.worklist.QueueOrdering;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Admits an unmatched presentation to the queue.
 *
 * <p>Three requirements meet here and it is worth naming which is which.
 *
 * <p><b>Only unmatched presentations are admitted</b> (FR-INT-01). The rule lives in
 * {@link PresentationView#unmatched()}, once, so that "why is this in the queue?" is never
 * re-derived somewhere else with a slightly different reading.
 *
 * <p><b>Admission is idempotent</b> (FR-INT-05), and enforced by a unique constraint rather than a
 * check-then-insert. Two concurrent redeliveries would both pass a check; only one can win a
 * unique index. This matters because the gateway retries from its own store (FR-INT-02b), so
 * redelivery is the expected case rather than an anomaly.
 *
 * <p><b>The presentation is stored verbatim</b> (FR-INT-04), and only the fields the worklist
 * needs are copied out (FR-INT-06).
 */
@Service
public class PresentationIntakeService {

    private static final Logger log = LoggerFactory.getLogger(PresentationIntakeService.class);

    /** What happened to a delivery. The gateway needs all three answers (FR-INT-02b). */
    public enum Outcome {
        /** New item, committed. */
        ADMITTED,
        /** Already in the queue or already decided. Nothing changed; do not retry. */
        ALREADY_HELD,
        /** Not admissible — matched, or missing the identity to key it on. Do not retry. */
        REJECTED
    }

    /**
     * @param outcome what happened
     * @param itemKey the identity assigned, where there is one
     * @param reason  why it was refused, for the gateway's own audit trail
     */
    public record Result(Outcome outcome, String itemKey, String reason) {}

    private final ReviewItemRepository items;
    private final QueueOrdering ordering;
    private final DataItemMapping mapping;
    private final AuditService audit;
    private final Clock clock;

    public PresentationIntakeService(ReviewItemRepository items, QueueOrdering ordering,
                                     DataItemMapping mapping, AuditService audit, Clock clock) {
        this.items = items;
        this.ordering = ordering;
        this.mapping = mapping;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Admits, or explains why not. Commits before it returns, so the gateway's acknowledgement
     * means the item is durably in the queue rather than in flight (FR-INT-02b).
     */
    @Transactional
    public Result admit(JsonNode presentationJson, String correlationId) {
        PresentationView presentation = new PresentationView(presentationJson);

        Optional<String> sessionId = presentation.sessionId();
        if (sessionId.isEmpty()) {
            // Without user._id there is no stable identity, so idempotency is impossible and a
            // redelivery would duplicate a patient in the queue. Refusing is the safe answer.
            audit.recordSystem(AuditService.SYSTEM_ACTOR_GATEWAY, AuditEvent.PRESENTATION_REJECTED,
                    null, correlationId, "No externalValue.user._id, so no stable item key");
            return new Result(Outcome.REJECTED, null, "The presentation carries no user._id");
        }

        String itemKey = sessionId.get();

        if (!presentation.unmatched()) {
            audit.recordSystem(AuditService.SYSTEM_ACTOR_GATEWAY, AuditEvent.PRESENTATION_REJECTED,
                    itemKey, correlationId,
                    "Presentation carries PDS demographics, so it needs no review");
            return new Result(Outcome.REJECTED, itemKey,
                    "The presentation is already matched to PDS");
        }

        if (items.existsByItemKey(itemKey)) {
            audit.recordSystem(AuditService.SYSTEM_ACTOR_GATEWAY,
                    AuditEvent.PRESENTATION_ALREADY_HELD, itemKey, correlationId,
                    "Redelivered; the existing item is unchanged");
            return new Result(Outcome.ALREADY_HELD, itemKey, null);
        }

        Instant receivedAt = Instant.now(clock);
        QueueOrdering.Wait wait = ordering.waitFrom(presentation, receivedAt);
        String triageScore = presentation.triageScore().orElse(null);

        ReviewItem item = ReviewItem.admit(itemKey, correlationId, presentationJson.toString(),
                presentation.unmatchedReason(), receivedAt, triageScore,
                ordering.rankFor(triageScore), wait.since(), wait.basis());

        describe(item, presentation);

        try {
            items.save(item);
            items.flush();
        } catch (DataIntegrityViolationException e) {
            // Lost the race to a concurrent redelivery. The other one is committed, which is
            // exactly the outcome wanted — say so rather than failing the gateway's POST.
            log.debug("Concurrent redelivery of {} lost the unique-key race", itemKey);
            audit.recordSystem(AuditService.SYSTEM_ACTOR_GATEWAY,
                    AuditEvent.PRESENTATION_ALREADY_HELD, itemKey, correlationId,
                    "Concurrent redelivery; the other delivery was admitted");
            return new Result(Outcome.ALREADY_HELD, itemKey, null);
        }

        audit.recordSystem(AuditService.SYSTEM_ACTOR_GATEWAY, AuditEvent.PRESENTATION_ADMITTED,
                itemKey, correlationId, "Admitted: " + item.getUnmatchedReason()
                        + ", triage score " + (triageScore == null ? "none" : triageScore)
                        + ", waiting from " + wait.basis());
        return new Result(Outcome.ADMITTED, itemKey, null);
    }

    /**
     * Copies out what the worklist shows. Uses the same configured mapping as the searches, so a
     * name shown on a row and a name sent to PDS are derived one way, not two (FR-PDS-03).
     */
    private void describe(ReviewItem item, PresentationView presentation) {
        DataItemMapping.Derived derived = mapping.derive(presentation.answers());
        String given = derived.submitted().get("given");
        String family = derived.submitted().get("family");
        String name = List.of(given == null ? "" : given, family == null ? "" : family).stream()
                .filter(part -> !part.isBlank())
                .reduce((a, b) -> a + " " + b)
                .orElse("(no name given)");

        item.describePatient(name, derived.submitted().get("birthdate"));
        item.describeContext(presentation.hospitalName().orElse(null),
                presentation.departmentCode().orElse(null),
                presentation.departmentName().orElse(null));
    }
}
