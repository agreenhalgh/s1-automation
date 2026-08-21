package com.newbrid.epr_automation.systmone.sdk.engine;

import com.newbrid.epr_automation.systmone.automation.window.NoopWindowControl;
import com.newbrid.epr_automation.systmone.common.dto.BotDefinition;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.dto.RetryPolicyDef;
import com.newbrid.epr_automation.systmone.common.dto.StepDefinition;
import com.newbrid.epr_automation.systmone.common.model.ActionType;
import com.newbrid.epr_automation.systmone.common.model.LocatorType;
import com.newbrid.epr_automation.systmone.common.model.NameMatch;
import com.newbrid.epr_automation.systmone.common.model.RunStatus;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACTIVATE is a window-level action the engine performs itself, so it needs neither a
 * locator chain nor a usable backend. The Win32 layer needs a desktop, so the port is
 * faked here and only the engine's contract is asserted.
 */
class WindowActivationTest {

    /** Records what the engine asked for, and can be told to refuse. */
    static class FakeWindowControl implements WindowControl {
        final List<String> activated = new ArrayList<>();
        String problem = null;

        @Override public boolean isAvailable() { return true; }

        @Override public String activate(String titleContains) {
            activated.add(titleContains);
            if (problem != null) throw new WindowActivationException(problem);
            return (titleContains == null ? "SystmOne" : titleContains) + " - Live";
        }

        @Override public List<String> titlesMatching(String titleContains) { return List.of(); }
        @Override public boolean isForeground(String titleContains) { return false; }
    }

    /** Resolves anything, so a gated ACTIVATE step gets past its chain. */
    static class FakeBackend implements AutomationBackend {
        boolean available = true;
        int performCount = 0;

        @Override public LocatorType handles() { return LocatorType.JAB; }
        @Override public boolean isAvailable() { return available; }

        @Override public Optional<ElementHandle> resolve(LocatorDefinition l, Duration timeout) {
            return Optional.of(new ElementHandle() {
                @Override public Rectangle screenBounds() { return new Rectangle(0, 0, 10, 10); }
                @Override public String description() { return "fake-frame"; }
            });
        }

        @Override public String perform(ActionType a, ElementHandle e, ActionParams p) {
            performCount++;
            return null;
        }

        @Override public BufferedImage captureScreenshot() { return null; }
    }

    private static StepDefinition activateStep(String inputValue, LocatorDefinition... chain) {
        return TestSteps.step("Activate", ActionType.ACTIVATE, chain)
                .inputValue(inputValue).outputKey("activatedWindow").build();
    }

    private static BotDefinition bot(StepDefinition... steps) {
        return new BotDefinition("bot-1", "Test bot", 1, "s1", List.of(steps), 0, RetryPolicyDef.none());
    }

    private static RunResult run(BackendRegistry reg, WindowControl windows, StepDefinition step) {
        return new BotEngine(reg, ScreenshotSink.NONE, windows)
                .run(bot(step), Map.of(), "run-1", RunListener.NOOP);
    }

    @Test
    void activatesWithNoLocatorChainAtAll() {
        FakeWindowControl windows = new FakeWindowControl();

        RunResult result = run(new BackendRegistry(), windows, activateStep(null));

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(1, windows.activated.size(), "the window should have been activated once");
        assertEquals(List.of("SystmOne - Live"), List.of(result.outputs().get("activatedWindow")));
    }

    @Test
    void activatesWithNoBackendRegisteredAtAll() {
        // The point of not routing through a backend: a step whose job is to fix focus
        // must work on a host where JAB is disabled and the JVM is headless.
        FakeWindowControl windows = new FakeWindowControl();

        RunResult result = run(new BackendRegistry(), windows, activateStep("Appointment Book"));

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(List.of("Appointment Book"), windows.activated);
    }

    @Test
    void blankInputValueLeavesTheTargetToTheWindowControl() {
        FakeWindowControl windows = new FakeWindowControl();

        run(new BackendRegistry(), windows, activateStep(null));

        assertEquals(1, windows.activated.size());
        assertEquals(null, windows.activated.get(0),
                "no title means the configured default, resolved by WindowControl");
    }

    @Test
    void inputValueResolvesRunPlaceholders() {
        FakeWindowControl windows = new FakeWindowControl();
        StepDefinition step = activateStep("${windowTitle}");

        new BotEngine(new BackendRegistry(), ScreenshotSink.NONE, windows)
                .run(bot(step), Map.of("windowTitle", "Appointment Book"), "run-2", RunListener.NOOP);

        assertEquals(List.of("Appointment Book"), windows.activated);
    }

    @Test
    void anOptionalChainStillGatesTheStepButTheWindowIsWhatIsActedOn() {
        FakeWindowControl windows = new FakeWindowControl();
        FakeBackend jab = new FakeBackend();
        BackendRegistry reg = new BackendRegistry().register(jab);

        RunResult result = run(reg, windows,
                activateStep(null, LocatorDefinition.jab("frame", "SystmOne", NameMatch.CONTAINS)));

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(1, windows.activated.size(), "the window is still what gets activated");
        assertEquals(0, jab.performCount, "the backend must never be asked to perform ACTIVATE");
    }

    @Test
    void aGatingChainThatCannotResolveStillFailsTheStep() {
        FakeWindowControl windows = new FakeWindowControl();
        FakeBackend jab = new FakeBackend();
        jab.available = false;                 // chain present but unusable
        BackendRegistry reg = new BackendRegistry().register(jab);

        RunResult result = run(reg, windows,
                activateStep(null, LocatorDefinition.jab("frame", "SystmOne", NameMatch.CONTAINS)));

        assertEquals(RunStatus.FAILED, result.status());
        assertTrue(windows.activated.isEmpty(), "a failed gate must not activate anything");
    }

    @Test
    void refusedActivationFailsTheStep() {
        // Fail-safe: a window that did not come to the front must not be reported as
        // activated, or the next KEYS step types into whatever is actually in front.
        FakeWindowControl windows = new FakeWindowControl();
        windows.problem = "Ambiguous window title 'SystmOne' matched 2 windows";

        RunResult result = run(new BackendRegistry(), windows, activateStep(null));

        assertEquals(RunStatus.FAILED, result.status());
        assertTrue(result.error().message().contains("matched 2 windows"), result.error().message());
    }

    @Test
    void anEngineWithoutWindowControlFailsRatherThanNoOps() {
        RunResult result = new BotEngine(new BackendRegistry())
                .run(bot(activateStep(null)), Map.of(), "run-3", RunListener.NOOP);

        assertEquals(RunStatus.FAILED, result.status());
        assertTrue(result.error().message().contains("no WindowControl configured"),
                result.error().message());
    }

    @Test
    void noopWindowControlExplainsWhyItCannotActivate() {
        WindowControl windows = new NoopWindowControl("window activation needs Windows");

        RunResult result = run(new BackendRegistry(), windows, activateStep("SystmOne"));

        assertEquals(RunStatus.FAILED, result.status());
        assertTrue(result.error().message().contains("needs Windows"), result.error().message());
    }

    @Test
    void stepsOtherThanActivateStillRequireALocator() {
        RunResult result = run(new BackendRegistry(), new FakeWindowControl(),
                TestSteps.step("Click", ActionType.CLICK).build());

        assertEquals(RunStatus.FAILED, result.status());
        assertTrue(result.error().message().contains("has no locators"), result.error().message());
    }

    @Test
    void noBackendImplementsActivate() {
        // Kept explicit so a future backend author gets a clear error rather than a
        // silently ignored switch case.
        FakeBackend backend = new FakeBackend();
        assertEquals(0, backend.performCount);

        var jab = new com.newbrid.epr_automation.systmone.automation.jab.JabBackend(
                new com.newbrid.epr_automation.systmone.automation.jab.NullAccessBridge("test"),
                null, "SystmOne");
        ElementHandle handle = new com.newbrid.epr_automation.systmone.automation.jab.JabElementHandle(
                new Rectangle(0, 0, 10, 10),
                new com.newbrid.epr_automation.systmone.automation.jab.AcRef(1, 1L), "frame",
                new com.newbrid.epr_automation.systmone.automation.jab.NullAccessBridge("test"));

        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> jab.perform(ActionType.ACTIVATE, handle, ActionParams.NONE));
        assertTrue(e.getMessage().contains("acts on a window"), e.getMessage());
    }
}
