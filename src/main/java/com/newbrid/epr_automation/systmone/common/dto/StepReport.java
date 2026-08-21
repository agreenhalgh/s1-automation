package com.newbrid.epr_automation.systmone.common.dto;

import com.newbrid.epr_automation.systmone.common.model.StepStatus;

/**
 * Per-step outcome POSTed to the control plane as each step completes (FR-WRK-03,
 * FR-ENG-06). Idempotently upserted by (runId, sequence).
 *
 * @param index           the step's <em>position</em> in the definition, which is what the engine
 *                        ran — not a number the definition declared. A conditional flow can run
 *                        one position several times, so this identifies the step but not the
 *                        execution
 * @param sequence        which execution this is within the run, counting from 1. Together with
 *                        the run id it identifies the row uniquely, which is what lets a step
 *                        reached twice by a backward jump keep both outcomes instead of the second
 *                        overwriting the first
 * @param resolvedLocator which locator in the chain actually resolved (for diagnosis / fallback-rate metrics)
 * @param screenshotRef   reference to a failure screenshot stored by the worker/control plane (NFR-OBS-02)
 */
public record StepReport(
        int index,
        int sequence,
        String name,
        StepStatus status,
        String resolvedLocator,
        long durationMs,
        String output,
        ExceptionInfo error,
        String screenshotRef
) {}
