package com.newbrid.epr_automation.systmone.common.dto;

import com.newbrid.epr_automation.systmone.common.model.ActionType;

import java.util.List;

/**
 * One step of a bot: an action applied to the first element that resolves from an
 * ordered locator chain (FR-ENG-01/02).
 *
 * @param index          zero-based position in the bot
 * @param name           human label for logs/UI
 * @param action         the interaction to perform
 * @param locatorChain   ordered locators tried in turn (JAB first, image fallback)
 * @param inputValue     value for SET_TEXT / SELECT / KEYS; may contain {@code ${var}} placeholders
 *                       resolved from the run input
 * @param outputKey      if set, the value read (GET_TEXT / READ_STATE) is stored under this key in the run outputs
 * @param timeoutMs      per-step overall timeout budget shared across the locator chain
 * @param retry          retry policy for this step; {@code null} → bot default
 * @param continueOnFailure if true, a failed step is recorded but the run continues
 * @param clickOffsetX   optional click-point X offset from the centre of the resolved element
 *                       (image locators); for a DRAG step, the X component of the drag delta
 * @param clickOffsetY   optional click-point Y offset; for a DRAG step, the Y delta
 */
public record StepDefinition(
        int index,
        String name,
        ActionType action,
        List<LocatorDefinition> locatorChain,
        String inputValue,
        String outputKey,
        long timeoutMs,
        RetryPolicyDef retry,
        boolean continueOnFailure,
        Integer clickOffsetX,
        Integer clickOffsetY
) {
    public StepDefinition {
        if (locatorChain == null) locatorChain = List.of();
        if (timeoutMs <= 0) timeoutMs = 10_000;
    }
}
