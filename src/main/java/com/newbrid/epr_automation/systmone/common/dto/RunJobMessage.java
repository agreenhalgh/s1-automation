package com.newbrid.epr_automation.systmone.common.dto;

import java.util.Map;

/**
 * The job message published to the RabbitMQ work queue and consumed by exactly one
 * worker (FR-CP-01, FR-WRK-01). Carries the full {@link BotDefinition} so the worker
 * is self-contained, plus the run's input record (FR-ENG-04).
 *
 * @param runId         control-plane run id; correlates all POSTed results (FR-CP-02a)
 * @param capabilityTag optional pool/capability routing tag (FR-WRK-10)
 * @param attempt       redelivery attempt counter
 * @param dispatchedAt  epoch millis stamped by the control plane at publish time
 */
public record RunJobMessage(
        String runId,
        BotDefinition bot,
        Map<String, String> input,
        String capabilityTag,
        int attempt,
        long dispatchedAt
) {
    public RunJobMessage {
        if (input == null) input = Map.of();
    }
}
