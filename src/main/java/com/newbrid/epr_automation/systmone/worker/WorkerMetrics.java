package com.newbrid.epr_automation.systmone.worker;

import com.newbrid.epr_automation.systmone.common.dto.StepReport;
import com.newbrid.epr_automation.systmone.common.model.RunStatus;
import com.newbrid.epr_automation.systmone.common.model.StepStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What a worker knows and the control plane does not: per-step outcomes, how long each step
 * took, and which locator strategy actually resolved (NFR-OBS-03). Recorded locally at the
 * moment it happens, so a worker that cannot reach the control plane still accounts for the
 * work it did — the step POST is best-effort by design (see {@link PostingRunListener}), and
 * a metric that went missing with it would hide exactly the incident worth seeing.
 *
 * <p>Every series is tagged with the worker id by a registry customiser in
 * {@link WorkerConfig}, so a fleet of workstations stays separable in one Prometheus.
 */
@Component
@Profile("worker")
public class WorkerMetrics {

    private final MeterRegistry registry;
    /** Prefetch is 1 (FR-WRK-02), so this is 0 or 1 — "is this workstation busy right now". */
    private final AtomicInteger activeJobs = new AtomicInteger();

    public WorkerMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("systmone.worker.jobs.active", activeJobs::get)
                .description("Jobs this worker is executing (0 or 1 — prefetch is 1)")
                .register(registry);
    }

    /** A job was claimed off the queue. Paired with {@link #jobEnded()} in a finally. */
    public void jobStarted() {
        activeJobs.incrementAndGet();
    }

    /** The job is no longer running, whether or not it produced an outcome worth naming. */
    public void jobEnded() {
        activeJobs.decrementAndGet();
    }

    /** How the job ended, and how long the whole delivery took. */
    public void jobOutcome(RunStatus status, Duration took) {
        String outcome = status == null ? "unknown" : status.name();
        registry.counter("systmone.worker.jobs", "outcome", outcome).increment();
        if (took != null) {
            registry.timer("systmone.worker.job.duration", "outcome", outcome).record(took);
        }
    }

    /**
     * One completed step. Two things are counted from it: the step's own outcome and
     * duration, and which locator strategy resolved it.
     *
     * <p>The second is the locator-fallback rate NFR-OBS-03 asks for. A bot's chain normally
     * leads with a JAB locator and falls back to image matching, so a rising share of
     * {@code strategy="IMAGE"} resolutions is the signal that a SystmOne upgrade has moved
     * the accessibility tree out from under the definitions — visible as a trend long before
     * it becomes a failed run, which is the point of measuring it rather than the failures.
     */
    public void stepCompleted(StepReport report) {
        if (report == null) return;
        String status = report.status() == null ? "unknown" : report.status().name();
        registry.counter("systmone.steps", "status", status).increment();
        registry.timer("systmone.step.duration", "status", status)
                .record(Duration.ofMillis(Math.max(0, report.durationMs())));

        String strategy = strategyOf(report.resolvedLocator());
        if (strategy != null) {
            registry.counter("systmone.locator.attempts",
                    "strategy", strategy,
                    "outcome", report.status() == StepStatus.SUCCEEDED ? "resolved" : "unresolved")
                    .increment();
        }
    }

    /**
     * The strategy out of a {@code resolvedLocator} description — {@code JAB[...]},
     * {@code IMAGE[...]}, or the {@code WINDOW[...]} / {@code KEYBOARD[...]} forms an
     * ACTIVATE or KEYS step reports instead of a locator. Parsed rather than passed through
     * because the engine deliberately reports a human-readable description, and bending that
     * into a machine field would cost the diagnosability it exists for. Null when the step
     * never got as far as trying one, which is not a locator outcome and is not counted as
     * a failed match.
     */
    private static String strategyOf(String resolvedLocator) {
        if (resolvedLocator == null || resolvedLocator.isBlank()) return null;
        int bracket = resolvedLocator.indexOf('[');
        String head = (bracket > 0 ? resolvedLocator.substring(0, bracket) : resolvedLocator).trim();
        // Bounded on purpose: anything unrecognised becomes one "other" series rather than a
        // new label value per step, which is how a metrics endpoint turns into a memory leak.
        return switch (head) {
            case "JAB", "IMAGE", "WINDOW", "KEYBOARD" -> head;
            default -> "other";
        };
    }
}
