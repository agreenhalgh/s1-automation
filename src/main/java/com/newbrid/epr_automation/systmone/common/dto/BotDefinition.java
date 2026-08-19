package com.newbrid.epr_automation.systmone.common.dto;

import java.util.List;

/**
 * A serialisable bot definition — the version-controlled, declarative form of an
 * automation (NFR-MNT-03). Stored as JSON in the control-plane registry and embedded
 * in the dispatched job so a worker is self-contained.
 *
 * @param globalTimeoutMs hard ceiling for the whole run (FR-ENG-08); 0 → no ceiling
 * @param defaultRetry    retry policy applied to steps that do not declare their own
 */
public record BotDefinition(
        String botId,
        String name,
        int version,
        String s1Version,
        List<StepDefinition> steps,
        long globalTimeoutMs,
        RetryPolicyDef defaultRetry
) {
    public BotDefinition {
        if (steps == null) steps = List.of();
        if (defaultRetry == null) defaultRetry = RetryPolicyDef.defaults();
    }
}
