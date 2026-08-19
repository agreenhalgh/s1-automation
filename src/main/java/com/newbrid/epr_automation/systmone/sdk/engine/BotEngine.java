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

    public BotEngine(BackendRegistry backends) {
        this(backends, ScreenshotSink.NONE, WindowControl.UNAVAILABLE);
    }

    public BotEngine(BackendRegistry backends, ScreenshotSink screenshotSink) {
        this(backends, screenshotSink, WindowControl.UNAVAILABLE);
    }

    public BotEngine(BackendRegistry backends, ScreenshotSink screenshotSink, WindowControl windows) {
        this.backends = backends;
        this.screenshotSink = screenshotSink == null ? ScreenshotSink.NONE : screenshotSink;
        this.windows = windows == null ? WindowControl.UNAVAILABLE : windows;
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

        for (StepDefinition step : bot.steps()) {
            if (aborted.getAsBoolean()) {
                terminal = RunStatus.ABORTED;
                runError = new ExceptionInfo("Aborted", "Run aborted before step " + step.index(), null, step.name());
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                terminal = RunStatus.TIMED_OUT;
                runError = new ExceptionInfo("Timeout", "Global run timeout exceeded before step " + step.index(),
                        null, step.name());
                break;
            }

            listener.onProgress(step.index());
            StepReport report = executeStep(bot, step, ctx, runId, deadline, aborted);
            reports.add(report);
            listener.onStepComplete(report);

            if (report.status() == StepStatus.FAILED && !step.continueOnFailure()) {
                terminal = RunStatus.FAILED;
                runError = report.error();
                break;
            }
        }

        Map<String, String> outputs = ctx.outputs();
        listener.onRunComplete(terminal, runError, outputs);
        return new RunResult(terminal, runError, outputs, reports);
    }

    private StepReport executeStep(BotDefinition bot, StepDefinition step, ExecutionContext ctx,
                                   String runId, long deadline, BooleanSupplier aborted) {
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
                    return new StepReport(step.index(), step.name(), StepStatus.SUCCEEDED,
                            result.locatorDesc, System.currentTimeMillis() - start, result.output, null, null);
                }
                lastError = new StepResolutionException(
                        "No locator in the chain resolved for step '" + step.name() + "'"
                                + (result.reason != null ? " — " + result.reason : ""));
            } catch (Throwable t) {
                lastError = t;
                log.debug("Step '{}' attempt {}/{} failed: {}", step.name(), attempt,
                        policy.maxAttempts(), t.toString());
            }
        }

        // Exhausted — record a diagnosable failure with a screenshot (FR-JAB-10, NFR-OBS-02).
        String screenshotRef = captureFailureScreenshot(runId, step.index());
        ExceptionInfo error = ExceptionInfo.from(
                lastError != null ? lastError : new StepResolutionException("Step failed"),
                "locator=" + lastLocatorDesc);
        StepStatus status = step.continueOnFailure() ? StepStatus.SKIPPED : StepStatus.FAILED;
        return new StepReport(step.index(), step.name(), status, lastLocatorDesc,
                System.currentTimeMillis() - start, null, error, screenshotRef);
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
            ElementHandle element = resolved.get();
            String output = act(backend, step, element, ctx);
            return new Attempt(true, describe(locator) + " → " + element.description(), output, null);
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

    private static String describe(LocatorDefinition l) {
        if (l.type() == com.newbrid.epr_automation.systmone.common.model.LocatorType.JAB) {
            return "JAB[role=" + l.role() + ", name=" + l.name() + "]";
        }
        return "IMAGE[" + l.templateRef() + "@" + l.threshold() + "]";
    }

    /** @param reason why nothing resolved, when that is more specific than "no match" */
    private record Attempt(boolean resolved, String locatorDesc, String output, String reason) {}
}
