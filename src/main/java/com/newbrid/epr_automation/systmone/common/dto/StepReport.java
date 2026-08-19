package com.newbrid.epr_automation.systmone.common.dto;

import com.newbrid.epr_automation.systmone.common.model.StepStatus;

/**
 * Per-step outcome POSTed to the control plane as each step completes (FR-WRK-03,
 * FR-ENG-06). Idempotently upserted by (runId, index).
 *
 * @param resolvedLocator which locator in the chain actually resolved (for diagnosis / fallback-rate metrics)
 * @param screenshotRef   reference to a failure screenshot stored by the worker/control plane (NFR-OBS-02)
 */
public record StepReport(
        int index,
        String name,
        StepStatus status,
        String resolvedLocator,
        long durationMs,
        String output,
        ExceptionInfo error,
        String screenshotRef
) {}
