package com.newbrid.epr_automation.systmone.sdk.engine;

import com.newbrid.epr_automation.systmone.common.dto.BotDefinition;
import com.newbrid.epr_automation.systmone.common.dto.ExceptionInfo;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.dto.RetryPolicyDef;
import com.newbrid.epr_automation.systmone.common.dto.StepDefinition;
import com.newbrid.epr_automation.systmone.common.dto.StepReport;
import com.newbrid.epr_automation.systmone.common.model.ActionType;
import com.newbrid.epr_automation.systmone.common.model.LocatorType;
import com.newbrid.epr_automation.systmone.common.model.RunStatus;
import com.newbrid.epr_automation.systmone.common.model.StepStatus;
import com.newbrid.epr_automation.systmone.sdk.locator.ActionParams;
import com.newbrid.epr_automation.systmone.sdk.locator.AutomationBackend;
import com.newbrid.epr_automation.systmone.sdk.locator.BackendRegistry;
import com.newbrid.epr_automation.systmone.sdk.locator.ElementHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Executes a {@link BotDefinition} step by step against the registered backends.
 *
 * <p>Implements the core execution semantics of §3.3:</p>
 * <ul>
 *   <li>Ordered locator fallback chain, JAB first, image second (FR-ENG-02).</li>
 *   <li>Window-level ACTIVATE, which needs no locator and no backend.</li>
 *   <li>Retry-with-backoff per step (FR-ENG-03, NFR-REL-03).</li>
 *   <li>Fail-safe: never act on an unresolved/ambiguous element (NFR-REL-04).</li>
 *   <li>Per-step timeout and a global run deadline / graceful abort (FR-ENG-08).</li>
 *   <li>Structured per-step reporting via {@link RunListener} (FR-ENG-06).</li>
 * </ul>
 *
 * <p>The engine is stateless and thread-confined per run; a worker constructs one and
 * runs a single job on it at a time (FR-WRK-02).</p>
 */
public class BotEngine {

    private static final Logger log = LoggerFactory.getLogger(BotEngine.class);

    private final BackendRegistry backends;
    private final ScreenshotSink screenshotSink;
    private final WindowControl windows;
    private final KeyboardControl keyboard;

    public BotEngine(BackendRegistry backends) {
        this(backends, ScreenshotSink.NONE, WindowControl.UNAVAILABLE);
    }

    public BotEngine(BackendRegistry backends, ScreenshotSink screenshotSink) {
        this(backends, screenshotSink, WindowControl.UNAVAILABLE);
    }

    /**
     * Ceiling on how many steps one run may execute, however the flow branches. Generous enough
     * that no honest loop reaches it, and finite so a mistaken one ends with a diagnosis instead
     * of running until the global timeout — which a bot is free not to declare.
     */
    private static final int MAX_STEP_EXECUTIONS = 1000;

    public BotEngine(BackendRegistry backends, ScreenshotSink screenshotSink, WindowControl windows) {
        this(backends, screenshotSink, windows, KeyboardControl.UNAVAILABLE);
    }

    public BotEngine(BackendRegistry backends, ScreenshotSink screenshotSink, WindowControl windows,
                     KeyboardControl keyboard) {
        this.backends = backends;
        this.screenshotSink = screenshotSink == null ? ScreenshotSink.NONE : screenshotSink;
        this.windows = windows == null ? WindowControl.UNAVAILABLE : windows;
        this.keyboard = keyboard == null ? KeyboardControl.UNAVAILABLE : keyboard;
    }

    public RunResult run(BotDefinition bot, Map<String, String> input, String runId, RunListener listener) {
        return run(bot, input, runId, listener, () -> false);
    }

    public RunResult run(BotDefinition bot, Map<String, String> input, String runId,
                         RunListener listener, BooleanSupplier aborted) {
        ExecutionContext ctx = new ExecutionContext(input);
        List<StepReport> reports = new ArrayList<>();
        long deadline = bot.globalTimeoutMs() > 0
                ? System.currentTimeMillis() + bot.globalTimeoutMs()
                : Long.MAX_VALUE;

        listener.onRunStarted(runId);
        RunStatus terminal = RunStatus.SUCCEEDED;
        ExceptionInfo runError = null;

        List<StepDefinition> steps = bot.steps();
        Map<String, Integer> jumpTargets = jumpTargets(steps);
        int[] executionsAt = new int[steps.size()];
        int position = 0;
        int executed = 0;

        while (position < steps.size()) {
            StepDefinition step = steps.get(position);
            if (aborted.getAsBoolean()) {
                terminal = RunStatus.ABORTED;
                runError = new ExceptionInfo("Aborted", "Run aborted before step " + position, null, step.name());
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                terminal = RunStatus.TIMED_OUT;
                runError = new ExceptionInfo("Timeout", "Global run timeout exceeded before step " + position,
                        null, step.name());
                break;
            }
            // A backward jump is a loop, and a loop with a condition that never changes would
            // otherwise run until the global timeout — or forever, on a bot that declares none.
            if (++executed > MAX_STEP_EXECUTIONS) {
                terminal = RunStatus.FAILED;
                runError = new ExceptionInfo("FlowLoop", "Stopped after " + MAX_STEP_EXECUTIONS
                        + " step executions: the conditional flow is looping without making progress",
                        null, step.name());
                break;
            }

            // The author's own bound on a loop, checked before the step runs so the limit is a
            // number of executions rather than a number of executions plus one.
            int limit = step.executionLimit();
            if (limit > 0 && executionsAt[position] >= limit) {
                StepReport limitReport = limitReached(step, position, executed, limit);
                reports.add(limitReport);
                listener.onStepComplete(limitReport);
                if (step.onLimit() == null) {
                    terminal = RunStatus.FAILED;
                    runError = limitReport.error();
                    break;
                }
                Integer onLimitTarget = jumpTargets.get(step.onLimit());
                if (onLimitTarget == null) {
                    terminal = RunStatus.FAILED;
                    runError = unknownKey(step, step.onLimit());
                    break;
                }
                log.info("Step '{}' has run its limit of {} — continuing at '{}' (step {})",
                        step.name(), limit, step.onLimit(), onLimitTarget);
                position = onLimitTarget;
                continue;
            }

            listener.onProgress(position);
            executionsAt[position]++;
            StepOutcome outcome = executeStep(bot, step, position, executed, ctx, runId, deadline, aborted);
            StepReport report = outcome.report();
            reports.add(report);
            listener.onStepComplete(report);

            if (report.status() == StepStatus.FAILED && !step.continueOnFailure()) {
                terminal = RunStatus.FAILED;
                runError = report.error();
                break;
            }

            String jumpTo = outcome.resolved() ? step.onFound()
                    : outcome.unresolved() ? step.onMissing()
                    : null;
            if (jumpTo == null) {
                position++;
                continue;
            }
            Integer target = jumpTargets.get(jumpTo);
            if (target == null) {
                terminal = RunStatus.FAILED;
                runError = unknownKey(step, jumpTo);
                break;
            }
            log.info("Step '{}' {} — continuing at '{}' (step {})", step.name(),
                    outcome.resolved() ? "resolved" : "found nothing", jumpTo, target);
            position = target;
        }

        Map<String, String> outputs = ctx.outputs();
        listener.onRunComplete(terminal, runError, outputs);
        return new RunResult(terminal, runError, outputs, reports);
    }

    /**
     * @param position where this step sits in the list — which step this is
     * @param sequence which execution of the run this is, counting from 1 — which visit to that
     *                 step this is. A conditional flow can bring the run back to the same
     *                 position, and both visits are worth keeping
     */
    private StepOutcome executeStep(BotDefinition bot, StepDefinition step, int position, int sequence,
                                    ExecutionContext ctx, String runId, long deadline,
                                    BooleanSupplier aborted) {
        RetryPolicyDef policy = step.retry() != null ? step.retry() : bot.defaultRetry();
        long start = System.currentTimeMillis();
        Throwable lastError = null;
        String lastLocatorDesc = null;

        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            long backoff = policy.backoffBeforeAttempt(attempt);
            if (backoff > 0) sleep(Math.min(backoff, remaining(deadline)));
            if (aborted.getAsBoolean() || System.currentTimeMillis() >= deadline) break;

            try {
                Attempt result = tryResolveAndAct(step, ctx, deadline);
                lastLocatorDesc = result.locatorDesc;
                if (result.resolved) {
                    if (step.outputKey() != null && result.output != null) {
                        ctx.putOutput(step.outputKey(), result.output);
                    }
                    return new StepOutcome(new StepReport(position, sequence, step.name(),
                            StepStatus.SUCCEEDED, result.locatorDesc, System.currentTimeMillis() - start,
                            result.output, null, null), true, false);
                }
                lastError = new StepResolutionException(
                        "No locator in the chain resolved for step '" + step.name() + "': "
                                + describeChain(step)
                                + (result.reason != null ? " — " + result.reason : ""));
            } catch (Throwable t) {
                lastError = t;
                log.debug("Step '{}' attempt {}/{} failed: {}", step.name(), attempt,
                        policy.maxAttempts(), t.toString());
            }
        }

        // Exhausted — record a diagnosable failure with a screenshot (FR-JAB-10, NFR-OBS-02).
        Throwable cause = lastError != null ? lastError : new StepResolutionException("Step failed");
        // Nothing resolved, as opposed to something resolving and then throwing. Only the former
        // is an onMissing branch: an action that blew up is a fault, and routing it down the
        // "not there" path would bury it.
        boolean unresolved = cause instanceof StepResolutionException;
        String screenshotRef = captureFailureScreenshot(runId, position);
        ExceptionInfo error = ExceptionInfo.from(cause, "locator=" + lastLocatorDesc);
        // A declared onMissing makes absence an expected outcome, so the step is skipped rather
        // than failed and the run continues down the branch the author chose.
        boolean branchOnAbsence = unresolved && step.onMissing() != null;
        StepStatus status = step.continueOnFailure() || branchOnAbsence
                ? StepStatus.SKIPPED : StepStatus.FAILED;
        return new StepOutcome(new StepReport(position, sequence, step.name(), status, lastLocatorDesc,
                System.currentTimeMillis() - start, null, error, screenshotRef), false, unresolved);
    }

    /**
     * Registration rejects unknown targets, so a run only meets one when the definition was
     * dispatched from somewhere that did not validate it. Failing beats silently falling through
     * to a step the author did not choose.
     */
    private static ExceptionInfo unknownKey(StepDefinition step, String target) {
        return new ExceptionInfo("UnknownStepKey", "Step '" + step.name() + "' jumps to '" + target
                + "', which no step declares as its key", null, step.name());
    }

    /**
     * The row that records a loop hitting its bound. Written even when the flow carries on
     * afterwards: without it the history simply stops mentioning the step, and "the loop gave up"
     * is the single most useful thing to know about a run that did not do what was expected.
     */
    private static StepReport limitReached(StepDefinition step, int position, int sequence, int limit) {
        String message = "Step '" + step.name() + "' reached its maxExecutions of " + limit
                + (step.onLimit() == null
                        ? " and declares no onLimit, so the run stops here"
                        : " — continuing at '" + step.onLimit() + "'");
        return new StepReport(position, sequence, step.name(), StepStatus.SKIPPED, null, 0, null,
                new ExceptionInfo("StepLimit", message, null, step.name()), null);
    }

    /**
     * What the step did, beyond what its report says: whether it resolved, and — when it did
     * not — whether that was because nothing was there. The flow needs both to choose a branch,
     * and neither belongs in a report POSTed to the control plane.
     */
    private record StepOutcome(StepReport report, boolean resolved, boolean unresolved) {}

    /**
     * Keyed steps, by position. Keys are the only jump targets: positions deliberately are not,
     * because a numeric target silently means a different step the moment one is inserted above
     * it, and that is the mistake the old positional field invited.
     */
    private static Map<String, Integer> jumpTargets(List<StepDefinition> steps) {
        Map<String, Integer> targets = new LinkedHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            String key = steps.get(i).key();
            // First declaration wins; registration rejects duplicates, so this only decides what
            // an unvalidated definition does rather than defining behaviour anyone should rely on.
            if (key != null) targets.putIfAbsent(key, i);
        }
        return targets;
    }

    /** Try each locator in the chain in turn; act on the first that resolves. */
    private Attempt tryResolveAndAct(StepDefinition step, ExecutionContext ctx, long deadline) {
        List<LocatorDefinition> chain = step.locatorChain();
        if (chain.isEmpty()) {
            // ACTIVATE targets a window, not an element, so it needs nothing to resolve.
            // Requiring a locator here would also make the step that repairs focus depend
            // on a backend being usable — precisely what it is often there to fix.
            if (step.action() == ActionType.ACTIVATE) {
                String title = activate(step, ctx);
                return new Attempt(true, "WINDOW[" + title + "]", title, null);
            }
            // KEYS goes to whatever holds the keyboard focus, and always did: both backends
            // discard the element they resolve for it. A chain on a KEYS step is a gate, not a
            // target, so a step that wants no gate should not have to invent one — and inventing
            // one made the keystroke depend on a backend it never used.
            if (step.action() == ActionType.KEYS) {
                String combo = ctx.resolve(step.inputValue());
                if (combo == null || combo.isBlank()) {
                    throw new StepResolutionException("Step '" + step.name() + "' is a KEYS step with "
                            + "no inputValue, so there is nothing to send");
                }
                keyboard.keys(combo);
                return new Attempt(true, "KEYBOARD[" + combo + "]", null, null);
            }
            throw new StepResolutionException("Step '" + step.name() + "' has no locators");
        }
        long stepBudget = Math.min(step.timeoutMs(), remaining(deadline));
        long perLocator = Math.max(200, stepBudget / chain.size());
        String lastDesc = null;
        EnumSet<LocatorType> unavailable = EnumSet.noneOf(LocatorType.class);
        int tried = 0;

        for (LocatorDefinition locator : chain) {
            lastDesc = describe(locator);
            AutomationBackend backend = backends.get(locator.type());
            if (backend == null || !backend.isAvailable()) {
                // Not a failed match — the strategy never ran. Recorded so the step's
                // failure message can say so instead of blaming the template.
                unavailable.add(locator.type());
                log.debug("Backend for {} unavailable; trying next locator", locator.type());
                continue;
            }
            tried++;
            Optional<ElementHandle> resolved = backend.resolve(locator, Duration.ofMillis(perLocator));
            if (resolved.isEmpty()) {
                continue; // fail-safe: not confident → next locator
            }
            // The handle owns a native reference the target JVM is holding for us, so it is
            // closed as soon as the step has acted — including on the paths that act on nothing,
            // such as WAIT, which would otherwise pin one control per poll.
            try (ElementHandle element = resolved.get()) {
                String output = act(backend, step, element, ctx);
                return new Attempt(true, describe(locator) + " → " + element.description(), output, null);
            }
        }

        String reason = null;
        if (tried == 0) {
            // Every locator was skipped, so the step never actually looked for anything.
            reason = "no backend available for " + unavailable + " — the whole locator chain was skipped";
            log.warn("Step '{}': {}", step.name(), reason);
        } else if (!unavailable.isEmpty()) {
            reason = "backend unavailable for " + unavailable;
        }
        return new Attempt(false, lastDesc, null, reason);
    }

    private String act(AutomationBackend backend, StepDefinition step, ElementHandle element, ExecutionContext ctx) {
        ActionType action = step.action();
        if (action == ActionType.WAIT) {
            return null; // resolution alone satisfies a WAIT step
        }
        if (action == ActionType.ACTIVATE) {
            // A chain is optional for ACTIVATE; when present it only gated the step, so
            // the window is still what gets acted on — never the element it resolved.
            return activate(step, ctx);
        }
        ActionParams params = new ActionParams(
                ctx.resolve(step.inputValue()),
                action == ActionType.KEYS ? ctx.resolve(step.inputValue()) : null,
                step.clickOffsetX(), step.clickOffsetY());
        return backend.perform(action, element, params);
    }

    /** {@code inputValue} names the window to raise; blank means the configured application. */
    private String activate(StepDefinition step, ExecutionContext ctx) {
        return windows.activate(ctx.resolve(step.inputValue()));
    }

    private String captureFailureScreenshot(String runId, int stepIndex) {
        for (var type : com.newbrid.epr_automation.systmone.common.model.LocatorType.values()) {
            AutomationBackend b = backends.get(type);
            if (b != null && b.isAvailable()) {
                try {
                    BufferedImage img = b.captureScreenshot();
                    if (img != null) return screenshotSink.store(runId, stepIndex, img);
                } catch (Exception e) {
                    log.debug("Failure screenshot capture failed: {}", e.toString());
                }
            }
        }
        return null;
    }

    private static long remaining(long deadline) {
        return deadline == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, deadline - System.currentTimeMillis());
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Every locator the step tried, spelled out. The step's own failure message is the one place
     * an author reliably sees, and "no locator resolved" without saying what was asked for leaves
     * them guessing at which field of which locator was wrong. Safe to be verbose: this lands in
     * the error message, not in the 255-character resolvedLocator column.
     */
    private static String describeChain(StepDefinition step) {
        return step.locatorChain().isEmpty()
                ? "the step has no locators"
                : step.locatorChain().stream().map(LocatorDefinition::describe)
                        .collect(java.util.stream.Collectors.joining("; then "));
    }

    private static String describe(LocatorDefinition l) {
        if (l.type() == com.newbrid.epr_automation.systmone.common.model.LocatorType.JAB) {
            return "JAB[role=" + l.role() + ", name=" + l.name() + "]";
        }
        return "IMAGE[" + l.templateRef() + "@" + l.threshold() + "]";
    }

    /** @param reason why nothing resolved, when that is more specific than "no match" */
    private record Attempt(boolean resolved, String locatorDesc, String output, String reason) {}
}
