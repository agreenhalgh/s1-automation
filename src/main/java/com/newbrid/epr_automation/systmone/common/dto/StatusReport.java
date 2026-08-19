package com.newbrid.epr_automation.systmone.common.dto;

import com.newbrid.epr_automation.systmone.common.model.RunStatus;

import java.util.Map;

/**
 * Run-level status transition POSTed by the worker (FR-WRK-03). The terminal report
 * is only sent once the run has reached a terminal state, and the worker acks the
 * AMQP message only after this POST is accepted (FR-WRK-04). The endpoint is
 * idempotent on terminal status (FR-CP-02a).
 */
public record StatusReport(
        RunStatus status,
        ExceptionInfo error,
        Map<String, String> outputs
) {
    public StatusReport {
        if (outputs == null) outputs = Map.of();
    }
}
