package com.newbrid.epr_automation.systmone.sdk.engine;

import com.newbrid.epr_automation.systmone.common.dto.BotDefinition;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.dto.RetryPolicyDef;
import com.newbrid.epr_automation.systmone.common.dto.StepDefinition;
import com.newbrid.epr_automation.systmone.common.dto.StepReport;
import com.newbrid.epr_automation.systmone.common.model.ActionType;
import com.newbrid.epr_automation.systmone.common.model.LocatorType;
import com.newbrid.epr_automation.systmone.common.model.NameMatch;
import com.newbrid.epr_automation.systmone.common.model.RunStatus;
import com.newbrid.epr_automation.systmone.common.model.StepStatus;
import com.newbrid.epr_automation.systmone.sdk.locator.ActionParams;
import com.newbrid.epr_automation.systmone.sdk.locator.AutomationBackend;
import com.newbrid.epr_automation.systmone.sdk.locator.BackendRegistry;
import com.newbrid.epr_automation.systmone.sdk.locator.ElementHandle;
import com.newbrid.epr_automation.systmone.common.dto.TestSteps;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the execution semantics of §3.3: fallback chain, retry, and fail-safe. */
class BotEngineTest {

    /** A configurable stand-in backend used to drive the engine deterministically. */
    static class FakeBackend implements AutomationBackend {
        final LocatorType type;
        boolean available = true;
        boolean resolveEmpty = false;
        int matchCount = 0;
        String lastText;
        /** Resolve empty this many more times, then start resolving — a condition that changes. */
        int emptyResolvesRemaining = 0;
        int failResolvesRemaining = 0;
        int performCount = 0;

        FakeBackend(LocatorType type) { this.type = type; }

        @Override public LocatorType handles() { return type; }
        @Override public boolean isAvailable() { return available; }

        @Override public Optional<ElementHandle> resolve(LocatorDefinition locator, Duration timeout) {
            if (failResolvesRemaining > 0) { failResolvesRemaining--; throw new RuntimeException("transient"); }
            if (emptyResolvesRemaining > 0) { emptyResolvesRemaining--; return Optional.empty(); }
            if (resolveEmpty) return Optional.empty();
            return Optional.of(new ElementHandle() {
                @Override public Rectangle screenBounds() { return new Rectangle(0, 0, 10, 10); }
                @Override public String description() { return "fake-" + type; }
            });
        }

        @Override public int count(LocatorDefinition locator, java.time.Duration timeout) {
            if (type == LocatorType.IMAGE) {
                throw new UnsupportedOperationException("IMAGE locators cannot count");
            }
            return matchCount;
        }

        @Override public String perform(ActionType action, ElementHandle element, ActionParams params) {
            performCount++;
            if (action == ActionType.SET_TEXT) lastText = params.text();
            return action == ActionType.GET_TEXT ? "value" : null;
        }

        @Override public BufferedImage captureScreenshot() { return null; }
    }

    private BotDefinition bot(StepDefinition... steps) {
        return new BotDefinition("bot-1", "Test bot", 1, "s1", List.of(steps), 0, RetryPolicyDef.none());
    }

    private StepDefinition step(ActionType action, RetryPolicyDef retry, LocatorDefinition... chain) {
        return TestSteps.step("step", action, chain).retry(retry).build();
    }

    /** A named step, so a conditional flow has something to jump to. */
    private StepDefinition keyed(String key, ActionType action, LocatorDefinition... chain) {
        return TestSteps.keyed(key, action, chain).build();
    }

    /** A step that may only run so many times, with somewhere to go once it has. */
    private StepDefinition bounded(String key, int maxExecutions, String onLimit,
                                   LocatorDefinition... chain) {
        return TestSteps.keyed(key, ActionType.CLICK, chain)
                .maxExecutions(maxExecutions).onLimit(onLimit).build();
    }

    /** A step that sends the run somewhere else depending on whether its locator resolves. */
    private StepDefinition branching(String name, String onFound, String onMissing,
                                     LocatorDefinition... chain) {
        return TestSteps.step(name, ActionType.WAIT, chain).onFound(onFound).onMissing(onMissing).build();
    }

    @Test
    void fallsBackToImageWhenJabUnavailable() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        jab.available = false;                       // JAB not usable on this host
        FakeBackend image = new FakeBackend(LocatorType.IMAGE);
        BackendRegistry reg = new BackendRegistry().register(jab).register(image);

        BotDefinition bot = bot(step(ActionType.CLICK, RetryPolicyDef.none(),
                LocatorDefinition.jab("push button", "OK", NameMatch.EXACT),
                LocatorDefinition.image("ok.png", 0.9)));

        RunResult result = new BotEngine(reg).run(bot, Map.of(), "run-1", RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(1, image.performCount, "image backend should have performed the click");
        assertEquals(0, jab.performCount, "unavailable JAB backend must not act");
        assertTrue(result.steps().get(0).resolvedLocator().contains("IMAGE"));
    }

    @Test
    void unavailableImageBackendIsNamedInTheFailure() {
        // A headless JVM makes ScreenBackend report itself unavailable, so an image-only
        // chain is skipped entirely. The failure must say that rather than read as a
        // template that did not match (the whole point of the reason string).
        FakeBackend image = new FakeBackend(LocatorType.IMAGE);
        image.available = false;
        BackendRegistry reg = new BackendRegistry().register(image);

        BotDefinition bot = bot(step(ActionType.SET_TEXT, RetryPolicyDef.none(),
                LocatorDefinition.image("patient-registration-search", 0.8)));

        RunResult result = new BotEngine(reg).run(bot, Map.of(), "run-3", RunListener.NOOP);

        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(0, image.performCount, "unavailable backend must not act");
        String message = result.steps().get(0).error().message();
        assertTrue(message.contains("no backend available for [IMAGE]"),
                "failure should name the unavailable backend, was: " + message);
    }

    @Test
    void failsSafeWhenNothingResolves() {
        FakeBackend image = new FakeBackend(LocatorType.IMAGE);
        image.resolveEmpty = true;                   // never confident → never acts
        BackendRegistry reg = new BackendRegistry().register(image);

        BotDefinition bot = bot(step(ActionType.CLICK, RetryPolicyDef.none(),
                LocatorDefinition.image("missing.png", 0.95)));

        RunResult result = new BotEngine(reg).run(bot, Map.of(), "run-2", RunListener.NOOP);

        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(0, image.performCount, "must not act when not confident");
        assertEquals(StepStatus.FAILED, result.steps().get(0).status());
    }

    @Test
    void retriesTransientFailureThenSucceeds() {
        FakeBackend image = new FakeBackend(LocatorType.IMAGE);
        image.failResolvesRemaining = 1;             // first attempt blows up, second works
        BackendRegistry reg = new BackendRegistry().register(image);

        RetryPolicyDef retry = new RetryPolicyDef(3, 1, 2.0, 5);
        BotDefinition bot = bot(step(ActionType.CLICK, retry, LocatorDefinition.image("btn.png", 0.9)));

        RunResult result = new BotEngine(reg).run(bot, Map.of(), "run-3", RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(1, image.performCount);
    }

    // ---- step order and identity ----

    /**
     * Order comes from the list, and a report's index is the position the engine ran, not a
     * number the definition declared. The old positional field let those disagree, and then
     * every report described a run that had not happened.
     */
    @Test
    void runsStepsInListOrderAndReportsTheirPositions() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        BackendRegistry reg = new BackendRegistry().register(jab);
        StepDefinition first = keyed("first", ActionType.CLICK, LocatorDefinition.jab("a", "a", null));
        StepDefinition second = keyed("second", ActionType.CLICK, LocatorDefinition.jab("b", "b", null));
        StepDefinition third = keyed("third", ActionType.CLICK, LocatorDefinition.jab("c", "c", null));

        RunResult result = new BotEngine(reg).run(bot(first, second, third), Map.of(), "run-order",
                RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(List.of("first", "second", "third"),
                result.steps().stream().map(StepReport::name).toList());
        assertEquals(List.of(0, 1, 2), result.steps().stream().map(StepReport::index).toList());
    }

    // ---- conditional flow ----

    @Test
    void jumpsToTheNamedStepWhenTheLocatorResolves() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        BackendRegistry reg = new BackendRegistry().register(jab);

        RunResult result = new BotEngine(reg).run(bot(
                branching("dialog up?", "handle-dialog", null, LocatorDefinition.jab("dialog", "d", null)),
                keyed("skipped", ActionType.CLICK, LocatorDefinition.jab("a", "a", null)),
                keyed("handle-dialog", ActionType.CLICK, LocatorDefinition.jab("b", "b", null))),
                Map.of(), "run-found", RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(List.of("dialog up?", "handle-dialog"),
                result.steps().stream().map(StepReport::name).toList());
    }

    /**
     * A declared onMissing makes absence an outcome rather than a failure: the step is recorded
     * as skipped, the run stays healthy, and it continues where the author said.
     */
    @Test
    void jumpsToTheNamedStepWhenNothingResolvesWithoutFailingTheRun() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        jab.resolveEmpty = true;
        FakeBackend image = new FakeBackend(LocatorType.IMAGE);
        BackendRegistry reg = new BackendRegistry().register(jab).register(image);

        RunResult result = new BotEngine(reg).run(bot(
                branching("dialog up?", null, "carry-on", LocatorDefinition.jab("dialog", "d", null)),
                keyed("dialog-only", ActionType.CLICK, LocatorDefinition.jab("a", "a", null)),
                keyed("carry-on", ActionType.CLICK, LocatorDefinition.image("t", 0.9))),
                Map.of(), "run-missing", RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(List.of("dialog up?", "carry-on"),
                result.steps().stream().map(StepReport::name).toList());
        assertEquals(StepStatus.SKIPPED, result.steps().getFirst().status(),
                "the branch was taken, so the absent locator is not a failure");
    }

    /** Without onMissing, a locator that does not resolve is what it always was: a failure. */
    @Test
    void aMissingLocatorStillFailsTheStepWhenNoBranchIsDeclared() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        jab.resolveEmpty = true;
        BackendRegistry reg = new BackendRegistry().register(jab);

        RunResult result = new BotEngine(reg).run(bot(
                step(ActionType.CLICK, RetryPolicyDef.none(), LocatorDefinition.jab("a", "a", null))),
                Map.of(), "run-plain-miss", RunListener.NOOP);

        assertEquals(RunStatus.FAILED, result.status());
    }

    /** Falling through is the default: a step with no jump runs the next one in the list. */
    @Test
    void fallsThroughWhenTheBranchForThisOutcomeIsNotDeclared() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        BackendRegistry reg = new BackendRegistry().register(jab);

        RunResult result = new BotEngine(reg).run(bot(
                branching("found, but only onMissing is set", null, "elsewhere",
                        LocatorDefinition.jab("dialog", "d", null)),
                keyed("next in the list", ActionType.CLICK, LocatorDefinition.jab("a", "a", null)),
                keyed("elsewhere", ActionType.CLICK, LocatorDefinition.jab("b", "b", null))),
                Map.of(), "run-fallthrough", RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(List.of("found, but only onMissing is set", "next in the list", "elsewhere"),
                result.steps().stream().map(StepReport::name).toList());
    }

    /**
     * A backward jump is a loop, which is the point — "keep paging until the row appears".
     * A loop whose condition never changes has to end in a diagnosis rather than run forever,
     * since a bot is free to declare no global timeout at all.
     */
    @Test
    void stopsALoopThatNeverMakesProgress() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        BackendRegistry reg = new BackendRegistry().register(jab);

        RunResult result = new BotEngine(reg).run(bot(
                keyed("top", ActionType.CLICK, LocatorDefinition.jab("a", "a", null)),
                branching("always found", "top", null, LocatorDefinition.jab("b", "b", null))),
                Map.of(), "run-loop", RunListener.NOOP);

        assertEquals(RunStatus.FAILED, result.status());
        assertEquals("FlowLoop", result.error().type());
        assertTrue(result.error().message().contains("looping"), result.error().message());
    }

    /**
     * Registration rejects an unknown target, so a run only meets one when the definition
     * reached the worker without being validated. Failing beats quietly running whichever step
     * happens to be next.
     */
    @Test
    void failsOnAJumpToAKeyNoStepDeclares() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        BackendRegistry reg = new BackendRegistry().register(jab);

        RunResult result = new BotEngine(reg).run(bot(
                branching("go nowhere", "typo", null, LocatorDefinition.jab("a", "a", null)),
                keyed("not the target", ActionType.CLICK, LocatorDefinition.jab("b", "b", null))),
                Map.of(), "run-bad-key", RunListener.NOOP);

        assertEquals(RunStatus.FAILED, result.status());
        assertEquals("UnknownStepKey", result.error().type());
        assertTrue(result.error().message().contains("typo"), result.error().message());
    }

    /**
     * A loop that does terminate — "look again until it is there" — and the numbering that makes
     * its history readable. Positions repeat; the sequence does not, which is what lets each pass
     * keep its own recorded outcome.
     */
    @Test
    void numbersEveryExecutionEvenWhenTheFlowRevisitsAStep() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        // The thing being waited for gets its own backend, so only the probe sees it as absent.
        FakeBackend image = new FakeBackend(LocatorType.IMAGE);
        image.emptyResolvesRemaining = 2;    // absent for two passes, then present
        BackendRegistry reg = new BackendRegistry().register(jab).register(image);

        RunResult result = new BotEngine(reg).run(bot(
                keyed("top", ActionType.CLICK, LocatorDefinition.jab("a", "a", null)),
                branching("is it there yet?", null, "top", LocatorDefinition.image("row", 0.9)),
                keyed("done", ActionType.CLICK, LocatorDefinition.jab("b", "b", null))),
                Map.of(), "run-loop-ok", RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(List.of(0, 1, 0, 1, 0, 1, 2),
                result.steps().stream().map(StepReport::index).toList(),
                "the flow went round twice before falling through");
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7),
                result.steps().stream().map(StepReport::sequence).toList(),
                "every execution has its own sequence, so no pass overwrites another");
    }

    // ---- bounded loops ----

    /**
     * The point of the attribute: a loop that never converges gives up after the number of passes
     * the author allowed, and takes the path they chose — rather than grinding on to the engine's
     * own backstop and failing.
     */
    @Test
    void stopsLoopingAfterMaxExecutionsAndTakesTheOnLimitPath() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        FakeBackend image = new FakeBackend(LocatorType.IMAGE);
        image.resolveEmpty = true;                  // the row never appears, however long we look
        BackendRegistry reg = new BackendRegistry().register(jab).register(image);

        RunResult result = new BotEngine(reg).run(bot(
                bounded("page-down", 3, "give-up", LocatorDefinition.jab("a", "a", null)),
                branching("is it there yet?", null, "page-down", LocatorDefinition.image("row", 0.9)),
                keyed("give-up", ActionType.CLICK, LocatorDefinition.jab("b", "b", null))),
                Map.of(), "run-bounded", RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(List.of(0, 1, 0, 1, 0, 1, 0, 2),
                result.steps().stream().map(StepReport::index).toList(),
                "three passes, then the limit row for the fourth attempt, then the fallback");

        StepReport limitRow = result.steps().get(6);
        assertEquals(StepStatus.SKIPPED, limitRow.status());
        assertEquals("StepLimit", limitRow.error().type());
        assertTrue(limitRow.error().message().contains("maxExecutions of 3"), limitRow.error().message());
    }

    /** Without onLimit, running out is a failure — the loop was supposed to converge. */
    @Test
    void failsTheRunWhenTheLimitIsReachedAndNoOnLimitIsDeclared() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        FakeBackend image = new FakeBackend(LocatorType.IMAGE);
        image.resolveEmpty = true;
        BackendRegistry reg = new BackendRegistry().register(jab).register(image);

        RunResult result = new BotEngine(reg).run(bot(
                bounded("page-down", 2, null, LocatorDefinition.jab("a", "a", null)),
                branching("is it there yet?", null, "page-down", LocatorDefinition.image("row", 0.9))),
                Map.of(), "run-bounded-fail", RunListener.NOOP);

        assertEquals(RunStatus.FAILED, result.status());
        assertEquals("StepLimit", result.error().type());
        assertTrue(result.error().message().contains("declares no onLimit"), result.error().message());
    }

    /** The limit counts executions of that step, so a step reached once has spent one of them. */
    @Test
    void countsExecutionsPerStepNotPerRun() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        BackendRegistry reg = new BackendRegistry().register(jab);

        RunResult result = new BotEngine(reg).run(bot(
                bounded("once", 1, "after", LocatorDefinition.jab("a", "a", null)),
                keyed("after", ActionType.CLICK, LocatorDefinition.jab("b", "b", null))),
                Map.of(), "run-once", RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(List.of(0, 1), result.steps().stream().map(StepReport::index).toList(),
                "its one execution is spent normally; the limit only bites on a second visit");
    }

    /** A step with no limit is unbounded, as every step was before the attribute existed. */
    @Test
    void anAbsentLimitDoesNotBoundAnything() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        FakeBackend image = new FakeBackend(LocatorType.IMAGE);
        image.emptyResolvesRemaining = 4;
        BackendRegistry reg = new BackendRegistry().register(jab).register(image);

        RunResult result = new BotEngine(reg).run(bot(
                keyed("top", ActionType.CLICK, LocatorDefinition.jab("a", "a", null)),
                branching("is it there yet?", null, "top", LocatorDefinition.image("row", 0.9)),
                keyed("done", ActionType.CLICK, LocatorDefinition.jab("b", "b", null))),
                Map.of(), "run-unbounded", RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(11, result.steps().size(), "four fruitless passes, then the fifth found it");
    }

    /**
     * The failure an author actually reads. "No locator resolved" without saying what was asked
     * for leaves them guessing which field of which locator was wrong — which is the whole
     * difficulty of a locator that looks right and matches nothing.
     */
    @Test
    void theFailureNamesEveryLocatorTheStepTried() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        jab.resolveEmpty = true;
        FakeBackend image = new FakeBackend(LocatorType.IMAGE);
        image.resolveEmpty = true;
        BackendRegistry reg = new BackendRegistry().register(jab).register(image);

        StepDefinition step = TestSteps.step("Type the surname", ActionType.SET_TEXT,
                        LocatorDefinition.jab("text", null, null, null, 1, "dialog[Patient Registration]"),
                        LocatorDefinition.image("surname-field", 0.94))
                .build();

        RunResult result = new BotEngine(reg).run(bot(step), Map.of(), "run-diag", RunListener.NOOP);

        String message = result.error().message();
        assertTrue(message.contains("role=\"text\""), message);
        assertTrue(message.contains("index=1"), message);
        assertTrue(message.contains("ancestorPath=\"dialog[Patient Registration]\""), message);
        assertTrue(message.contains("templateRef=\"surname-field\""), message);
        assertTrue(message.contains("; then "), "both locators in the chain, in order: " + message);
    }

    // ---- KEYS without a locator ----

    /** Records what it was asked to send, and whether it was asked at all. */
    static class FakeKeyboard implements KeyboardControl {
        final List<String> sent = new ArrayList<>();
        boolean available = true;

        @Override public boolean isAvailable() { return available; }
        @Override public void keys(String combo) { sent.add(combo); }
    }

    /**
     * Keystrokes go to whatever holds the keyboard focus, and always did — both backends discard
     * the element they resolve for a KEYS action. So a step that wants no gate should not have to
     * invent a locator, and inventing one made the keystroke depend on a backend it never used.
     */
    @Test
    void sendsKeysWithNoLocatorChainAtAll() {
        FakeKeyboard keyboard = new FakeKeyboard();
        BotEngine engine = new BotEngine(new BackendRegistry(), ScreenshotSink.NONE,
                WindowControl.UNAVAILABLE, keyboard);

        RunResult result = engine.run(bot(TestSteps.step("Dismiss the dialog", ActionType.KEYS)
                .inputValue("ESCAPE").build()), Map.of(), "run-keys", RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(List.of("ESCAPE"), keyboard.sent);
        assertEquals("KEYBOARD[ESCAPE]", result.steps().getFirst().resolvedLocator(),
                "the report says where the keystrokes went, since no element was involved");
    }

    /** No backend registered at all, which is the point: it needs none. */
    @Test
    void needsNoBackendToSendKeys() {
        FakeKeyboard keyboard = new FakeKeyboard();
        BotEngine engine = new BotEngine(new BackendRegistry(), ScreenshotSink.NONE,
                WindowControl.UNAVAILABLE, keyboard);

        RunResult result = engine.run(bot(TestSteps.step("Sequence", ActionType.KEYS)
                .inputValue("DOWN,DOWN,ENTER").build()), Map.of(), "run-seq", RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(List.of("DOWN,DOWN,ENTER"), keyboard.sent, "the sequence is passed through whole");
    }

    @Test
    void resolvesRunPlaceholdersInAChainlessKeysStep() {
        FakeKeyboard keyboard = new FakeKeyboard();
        BotEngine engine = new BotEngine(new BackendRegistry(), ScreenshotSink.NONE,
                WindowControl.UNAVAILABLE, keyboard);

        RunResult result = engine.run(bot(TestSteps.step("Type the shortcut", ActionType.KEYS)
                        .inputValue("CTRL+${letter}").build()),
                Map.of("letter", "F"), "run-var", RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(List.of("CTRL+F"), keyboard.sent);
    }

    /** Reporting success for keystrokes that went nowhere would be the worst outcome. */
    @Test
    void failsWhenNoKeyboardIsConfigured() {
        BotEngine engine = new BotEngine(new BackendRegistry(), ScreenshotSink.NONE,
                WindowControl.UNAVAILABLE);

        RunResult result = engine.run(bot(TestSteps.step("Dismiss", ActionType.KEYS)
                .inputValue("ESCAPE").build()), Map.of(), "run-no-kb", RunListener.NOOP);

        assertEquals(RunStatus.FAILED, result.status());
        assertTrue(result.error().message().contains("no keyboard configured"), result.error().message());
    }

    /** A KEYS step with nothing to send is a definition mistake, not a no-op. */
    @Test
    void failsAChainlessKeysStepThatHasNothingToSend() {
        FakeKeyboard keyboard = new FakeKeyboard();
        BotEngine engine = new BotEngine(new BackendRegistry(), ScreenshotSink.NONE,
                WindowControl.UNAVAILABLE, keyboard);

        RunResult result = engine.run(bot(TestSteps.step("Send nothing", ActionType.KEYS).build()),
                Map.of(), "run-empty-keys", RunListener.NOOP);

        assertEquals(RunStatus.FAILED, result.status());
        assertTrue(result.error().message().contains("nothing to send"), result.error().message());
        assertEquals(List.of(), keyboard.sent);
    }

    /** Every other action still needs somewhere to act. */
    @Test
    void stillRequiresALocatorForActionsThatActOnAnElement() {
        BotEngine engine = new BotEngine(new BackendRegistry(), ScreenshotSink.NONE,
                WindowControl.UNAVAILABLE, new FakeKeyboard());

        RunResult result = engine.run(bot(TestSteps.step("Click nothing", ActionType.CLICK).build()),
                Map.of(), "run-no-loc", RunListener.NOOP);

        assertEquals(RunStatus.FAILED, result.status());
        assertTrue(result.error().message().contains("has no locators"), result.error().message());
    }

    // ---- GET_COUNT ----

    /** Several matches are the answer here, so counting must not go near the ambiguity fail-safe. */
    @Test
    void countsMatchesIntoTheRunOutputs() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        jab.matchCount = 12;
        BackendRegistry reg = new BackendRegistry().register(jab);

        RunResult result = new BotEngine(reg).run(bot(
                TestSteps.step("How many rows?", ActionType.GET_COUNT,
                                LocatorDefinition.jab("table row", null, null))
                        .outputKey("rowCount").build()),
                Map.of(), "run-count", RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals("12", result.outputs().get("rowCount"));
        assertEquals(0, jab.performCount, "counting acts on nothing");
    }

    /** Zero rows is a fact about the screen, not a failure to read it. */
    @Test
    void countingZeroSucceeds() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        jab.matchCount = 0;
        BackendRegistry reg = new BackendRegistry().register(jab);

        RunResult result = new BotEngine(reg).run(bot(
                TestSteps.step("How many rows?", ActionType.GET_COUNT,
                                LocatorDefinition.jab("table row", null, null))
                        .outputKey("rowCount").build()),
                Map.of(), "run-count-zero", RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals("0", result.outputs().get("rowCount"));
    }

    /** The count feeds the next step, which is the point of putting it in the outputs. */
    @Test
    void aCountedValueIsAvailableToLaterSteps() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        jab.matchCount = 7;
        BackendRegistry reg = new BackendRegistry().register(jab);

        RunResult result = new BotEngine(reg).run(bot(
                        TestSteps.step("How many rows?", ActionType.GET_COUNT,
                                        LocatorDefinition.jab("table row", null, null))
                                .outputKey("rowCount").build(),
                        TestSteps.step("Say how many", ActionType.SET_TEXT,
                                        LocatorDefinition.jab("text", null, null))
                                .inputValue("${rowCount} rows").build()),
                Map.of(), "run-count-then-use", RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals("7 rows", jab.lastText, "the count reached the next step as a placeholder");
    }

    /** An image locator declines to be counted, and the step says which locator and why. */
    @Test
    void failsWhenNoLocatorInTheChainCanBeCounted() {
        FakeBackend image = new FakeBackend(LocatorType.IMAGE);
        BackendRegistry reg = new BackendRegistry().register(image);

        RunResult result = new BotEngine(reg).run(bot(
                TestSteps.step("How many?", ActionType.GET_COUNT,
                                LocatorDefinition.image("row-strip", 0.9))
                        .outputKey("rows").build()),
                Map.of(), "run-count-image", RunListener.NOOP);

        assertEquals(RunStatus.FAILED, result.status());
        assertTrue(result.error().message().contains("cannot count"), result.error().message());
    }

    @Test
    void capturesReadOutputIntoRunOutputs() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        BackendRegistry reg = new BackendRegistry().register(jab);

        StepDefinition read = TestSteps.step("read", ActionType.GET_TEXT,
                        LocatorDefinition.jab("text", "NHS", NameMatch.CONTAINS))
                .outputKey("nhsNumber").build();

        RunResult result = new BotEngine(reg).run(bot(read), Map.of(), "run-4", RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals("value", result.outputs().get("nhsNumber"));
    }
}
