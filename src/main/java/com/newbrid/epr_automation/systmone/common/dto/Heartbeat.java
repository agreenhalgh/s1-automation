package com.newbrid.epr_automation.systmone.common.dto;

/**
 * Periodic liveness signal for a long-running run (FR-WRK-06). Lets the control
 * plane tell a slow run from a dead worker and reclaim/redispatch abandoned runs.
 */
public record Heartbeat(
        String workerId,
        Integer currentStepIndex
) {}
