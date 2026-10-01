package com.newbrid.epr_automation.pdsreview.worklist;

import com.newbrid.epr_automation.pdsreview.config.PdsReviewProperties;
import com.newbrid.epr_automation.pdsreview.domain.Decision;
import com.newbrid.epr_automation.pdsreview.domain.PostBackState;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItemRepository;
import com.newbrid.epr_automation.pdsreview.domain.ReviewState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * What a service manager watches (NFR-OBS-02).
 *
 * <p>Two of these are the ones worth alerting on, for opposite reasons.
 *
 * <p>{@code oldest_waiting_seconds} says whether the queue is coping. Depth alone does not: a
 * thousand items all arrived in the last ten minutes is a busy afternoon, and one item sitting for
 * six hours is a patient nobody has looked at.
 *
 * <p>{@code decisions_total} broken out by reason exists because of R13. The two closing outcomes
 * are the fastest way to make a worklist shorter, and under the queue pressure of R3 that is a
 * real temptation. Nobody is accused of anything by measuring it — but a closure rate that climbs
 * with the backlog is a conversation somebody should be having.
 *
 * <p>{@code post_back_failed} is R9: a decided item that never reached the control plane looks
 * finished from in here, and nothing else would surface it.
 */
@Component
public class QueueMetrics {

    private final ReviewItemRepository items;
    private final Clock clock;

    public QueueMetrics(ReviewItemRepository items, MeterRegistry registry,
                        PdsReviewProperties properties, Clock clock) {
        this.items = items;
        this.clock = clock;

        Gauge.builder("pds_review_queue_depth", () -> items.countByState(ReviewState.WAITING))
                .description("Presentations waiting for a reviewer")
                .register(registry);

        Gauge.builder("pds_review_in_progress", () -> items.countByState(ReviewState.IN_REVIEW))
                .description("Presentations currently held by a reviewer")
                .register(registry);

        Gauge.builder("pds_review_oldest_waiting_seconds", this::oldestWaitingSeconds)
                .description("Age of the oldest item still waiting. The number to alert on")
                .baseUnit("seconds")
                .register(registry);

        // Depth per triage score, so a backlog of P5s is distinguishable from a backlog that has
        // urgent patients in it.
        for (String score : properties.queue().triageScoreOrder()) {
            Gauge.builder("pds_review_queue_depth_by_score",
                            () -> items.countByStateAndTriageScore(ReviewState.WAITING, score))
                    .description("Presentations waiting, by triage score")
                    .tag("score", score)
                    .register(registry);
        }

        for (Decision decision : Decision.values()) {
            Gauge.builder("pds_review_decisions_total", () -> items.countByDecision(decision))
                    .description("Decisions recorded, by reason code")
                    .tag("decision", decision.name())
                    .tag("class", decision.decisionClass().name())
                    .register(registry);
        }

        Gauge.builder("pds_review_post_back_pending",
                        () -> items.countByPostBackState(PostBackState.PENDING))
                .description("Decisions written down but not yet acknowledged by the gateway")
                .register(registry);

        Gauge.builder("pds_review_post_back_failed",
                        () -> items.countByPostBackState(PostBackState.FAILED))
                .description("Decisions that will not be retried and have NOT reached the "
                        + "control plane. Any value above zero needs a human")
                .register(registry);
    }

    private double oldestWaitingSeconds() {
        Instant oldest = items.oldestWaitingSince();
        return oldest == null ? 0d : Duration.between(oldest, Instant.now(clock)).toSeconds();
    }
}
