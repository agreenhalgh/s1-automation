package com.newbrid.epr_automation.systmone.controlplane.service;

import com.newbrid.epr_automation.systmone.common.model.RunStatus;
import com.newbrid.epr_automation.systmone.controlplane.repo.RunRepository;
import com.newbrid.epr_automation.systmone.controlplane.repo.WorkerAgentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Run counts, durations and success/failure, plus agent health (NFR-OBS-03), published to
 * the Prometheus endpoint the collector in docker-compose.yaml scrapes.
 *
 * <p>The split with {@link com.newbrid.epr_automation.systmone.worker.WorkerMetrics} is
 * deliberate and not redundant: the control plane owns whole runs — it is the only place
 * that knows a run was dispatched and how long it took end to end — while a worker owns
 * steps and locators, which it can count even when the POST that would have reported them
 * never lands. Both sides carry a {@code role} tag, so the same question can be asked of
 * either.
 *
 * <p>Counters here are incremented on the transition, not derived from a query, because
 * "how many runs failed in the last hour" is a rate and a gauge over a table cannot answer
 * it — a row that succeeded on retry looks the same afterwards as one that never failed.
 * The gauges alongside them are for the opposite question, the one that is about right now:
 * how much work is in flight and how many agents are alive to take it.
 */
@Component
@Profile("controlplane")
public class RunMetrics {

    /** Statuses a run passes through rather than ends in — the in-flight ones worth a gauge. */
    private static final RunStatus[] IN_FLIGHT =
            {RunStatus.PENDING, RunStatus.DISPATCHED, RunStatus.RUNNING};

    private final MeterRegistry registry;

    public RunMetrics(MeterRegistry registry, RunRepository runs, WorkerAgentRepository workers) {
        this.registry = registry;

        for (RunStatus status : IN_FLIGHT) {
            // PENDING and DISPATCHED piling up mean nothing is consuming the queue; RUNNING
            // staying flat means a worker has stopped reporting. Neither shows up in a counter.
            Gauge.builder("systmone.runs.inflight", () -> runs.countByStatus(status))
                    .tag("status", status.name())
                    .description("Runs currently in this non-terminal state")
                    .register(registry);
        }

        Gauge.builder("systmone.workers.registered", workers::count)
                .description("Worker agents that have ever self-registered (FR-WRK-08)")
                .register(registry);

        for (String state : new String[]{"IDLE", "BUSY"}) {
            Gauge.builder("systmone.workers", () -> workers.countByStatus(state))
                    .tag("status", state)
                    .description("Worker agents in this state, as last reported to the control plane")
                    .register(registry);
        }
    }

    /** A run was created and published to the work queue (FR-CP-01). */
    public void dispatched(String botId) {
        registry.counter("systmone.runs.dispatched", "bot", nonNull(botId)).increment();
    }

    /**
     * A run reached a terminal state. Timed from {@code startedAt} — when a worker actually
     * picked it up — rather than from creation, so queue wait does not get counted as
     * execution time; a run that was never started is counted but not timed.
     */
    public void finished(String botId, RunStatus status, Instant startedAt, Instant finishedAt) {
        String bot = nonNull(botId);
        registry.counter("systmone.runs.finished", "bot", bot, "status", status.name()).increment();
        if (startedAt != null && finishedAt != null && !finishedAt.isBefore(startedAt)) {
            registry.timer("systmone.run.duration", "bot", bot, "status", status.name())
                    .record(Duration.between(startedAt, finishedAt));
        }
    }

    /**
     * Bot ids come from the registry, so the label set stays small and bounded — but a null
     * would become a series named after nothing at all, which is harder to spot than a
     * literal "unknown".
     */
    private static String nonNull(String botId) {
        return botId == null || botId.isBlank() ? "unknown" : botId;
    }
}
