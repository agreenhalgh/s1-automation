package com.newbrid.epr_automation.systmone.sdk.engine;

import com.newbrid.epr_automation.systmone.common.dto.BotDefinition;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.dto.RetryPolicyDef;
import com.newbrid.epr_automation.systmone.common.dto.StepDefinition;
import com.newbrid.epr_automation.systmone.common.model.ActionType;
import com.newbrid.epr_automation.systmone.common.model.LocatorType;
import com.newbrid.epr_automation.systmone.common.model.NameMatch;
import com.newbrid.epr_automation.systmone.common.model.RunStatus;
import com.newbrid.epr_automation.systmone.common.model.StepStatus;
import com.newbrid.epr_automation.systmone.sdk.locator.ActionParams;
import com.newbrid.epr_automation.systmone.sdk.locator.AutomationBackend;
import com.newbrid.epr_automation.systmone.sdk.locator.BackendRegistry;
import com.newbrid.epr_automation.systmone.sdk.locator.ElementHandle;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.Duration;
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
        int failResolvesRemaining = 0;
        int performCount = 0;

        FakeBackend(LocatorType type) { this.type = type; }

        @Override public LocatorType handles() { return type; }
        @Override public boolean isAvailable() { return available; }

        @Override public Optional<ElementHandle> resolve(LocatorDefinition locator, Duration timeout) {
            if (failResolvesRemaining > 0) { failResolvesRemaining--; throw new RuntimeException("transient"); }
            if (resolveEmpty) return Optional.empty();
            return Optional.of(new ElementHandle() {
                @Override public Rectangle screenBounds() { return new Rectangle(0, 0, 10, 10); }
                @Override public String description() { return "fake-" + type; }
            });
        }

        @Override public String perform(ActionType action, ElementHandle element, ActionParams params) {
            performCount++;
            return action == ActionType.GET_TEXT ? "value" : null;
        }

        @Override public BufferedImage captureScreenshot() { return null; }
    }

    private BotDefinition bot(StepDefinition... steps) {
        return new BotDefinition("bot-1", "Test bot", 1, "s1", List.of(steps), 0, RetryPolicyDef.none());
    }

    private StepDefinition step(ActionType action, RetryPolicyDef retry, LocatorDefinition... chain) {
        return new StepDefinition(0, "step", action, List.of(chain), null, null, 5000, retry, false, null, null);
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

    @Test
    void capturesReadOutputIntoRunOutputs() {
        FakeBackend jab = new FakeBackend(LocatorType.JAB);
        BackendRegistry reg = new BackendRegistry().register(jab);

        StepDefinition read = new StepDefinition(0, "read", ActionType.GET_TEXT,
                List.of(LocatorDefinition.jab("text", "NHS", NameMatch.CONTAINS)),
                null, "nhsNumber", 5000, RetryPolicyDef.none(), false, null, null);

        RunResult result = new BotEngine(reg).run(bot(read), Map.of(), "run-4", RunListener.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals("value", result.outputs().get("nhsNumber"));
    }
}
