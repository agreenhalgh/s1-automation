package com.newbrid.epr_automation.systmone.common.dto;

import com.newbrid.epr_automation.systmone.common.model.ActionType;

import java.util.List;

/**
 * One step of a bot: an action applied to the first element that resolves from an
 * ordered locator chain (FR-ENG-01/02).
 *
 * <p>Steps run in the order they appear in {@code steps}. There is no positional field to
 * keep in step with that order — an earlier version of this record carried one, and a bot
 * whose declared numbers disagreed with its array order got reports that described a run
 * that never happened. Position is the engine's to know; {@link #key} is the author's way of
 * naming a step, and it only needs to exist on the steps something jumps to.</p>
 *
 * @param key            optional name for this step, unique within the bot. Only needed as the
 *                       target of an {@link #onFound} or {@link #onMissing} jump
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
 * @param onFound        key of the step to continue from when this step's locator <em>resolves</em>
 *                       and its action succeeds. Without it, the next step in the list runs
 * @param onMissing      key of the step to continue from when no locator in the chain resolves.
 *                       Declaring it makes "not there" an outcome rather than a failure: the step
 *                       is recorded as skipped and the run continues at the named step. An action
 *                       that resolves and then throws is still a failure — only absence branches
 * @param maxExecutions  how many times this step may run in one run; {@code null} for no limit.
 *                       This is what bounds a loop built from {@link #onFound} / {@link #onMissing}:
 *                       "page down at most ten times". On the attempt that would exceed it the step
 *                       does not run, and the flow takes {@link #onLimit} if it is set
 * @param onLimit        key of the step to continue from once {@code maxExecutions} is used up.
 *                       Without it, exhausting the limit fails the run — which is the right default
 *                       for a loop that was supposed to converge, and the wrong one for a loop with
 *                       a fallback, so say which you have
 */
public record StepDefinition(
        String key,
        String name,
        ActionType action,
        List<LocatorDefinition> locatorChain,
        String inputValue,
        String outputKey,
        long timeoutMs,
        RetryPolicyDef retry,
        boolean continueOnFailure,
        Integer clickOffsetX,
        Integer clickOffsetY,
        String onFound,
        String onMissing,
        Integer maxExecutions,
        String onLimit
) {
    public StepDefinition {
        if (locatorChain == null) locatorChain = List.of();
        if (timeoutMs <= 0) timeoutMs = 10_000;
        key = blankToNull(key);
        onFound = blankToNull(onFound);
        onMissing = blankToNull(onMissing);
        onLimit = blankToNull(onLimit);
    }

    /**
     * How many times this step may run, or 0 for no limit. A non-positive declared value is
     * treated as no limit here — registration rejects it, so the engine only sees one when a
     * definition arrived unvalidated, and refusing to run a step at all is a worse reading of
     * "maxExecutions: 0" than ignoring it.
     */
    public int executionLimit() {
        return maxExecutions == null || maxExecutions < 1 ? 0 : maxExecutions;
    }

    /** True when this step decides where the run goes next. */
    public boolean branches() {
        return onFound != null || onMissing != null || onLimit != null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
