package com.newbrid.epr_automation.systmone.automation.jab;

import com.newbrid.epr_automation.systmone.automation.jab.FakeTree.Node;
import com.newbrid.epr_automation.systmone.common.dto.BotDefinition;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.dto.RetryPolicyDef;
import com.newbrid.epr_automation.systmone.common.dto.StepDefinition;
import com.newbrid.epr_automation.systmone.common.dto.StepReport;
import com.newbrid.epr_automation.systmone.common.dto.TestSteps;
import com.newbrid.epr_automation.systmone.common.model.ActionType;
import com.newbrid.epr_automation.systmone.common.model.NameMatch;
import com.newbrid.epr_automation.systmone.common.model.RunStatus;
import com.newbrid.epr_automation.systmone.sdk.engine.BotEngine;
import com.newbrid.epr_automation.systmone.sdk.engine.RunListener;
import com.newbrid.epr_automation.systmone.sdk.engine.RunResult;
import com.newbrid.epr_automation.systmone.sdk.engine.ScreenshotSink;
import com.newbrid.epr_automation.systmone.sdk.engine.WindowControl;
import com.newbrid.epr_automation.systmone.sdk.locator.BackendRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walking a grid: {@code row} taking a placeholder, and the loop counter that fills it.
 *
 * <p>Together these turn "address each row you need explicitly" into an actual loop. Neither is
 * useful alone — a counter with nowhere to put it, or a placeholder with nothing to resolve.</p>
 */
class JabTableLoopTest {

    private static final String[][] GRID = {
            {"Time", "Patient", "Row"},
            {"09:00", "Ada Lovelace", "1"},
            {"09:20", "Alan Turing", "2"},
            {"09:40", "Grace Hopper", "3"},
    };

    private static Node appointmentBook() {
        Node dialog = FakeTree.node("dialog", "Appointment Book", null, 0, 0, 900, 700, 0);
        dialog.add(FakeTree.node("table", "Appointments", null, 10, 100, 880, 300, 0)
                .asTable(GRID.length, GRID[0].length,
                        (row, column) -> FakeTree.node("label", GRID[row][column], null,
                                20 + column * 290, 130 + row * 26, 280, 24, column)));
        return dialog;
    }

    private RunResult run(Map<String, String> input, StepDefinition... steps) {
        BackendRegistry backends = new BackendRegistry()
                .register(new JabBackend(FakeTree.bridge(appointmentBook()), null, "SystmOne"));
        BotDefinition bot = new BotDefinition("walk-a-table", "Walk a table", 1, "2024",
                List.of(steps), 0, RetryPolicyDef.none());
        return new BotEngine(backends, ScreenshotSink.NONE, WindowControl.UNAVAILABLE)
                .run(bot, input, "run-table-loop", RunListener.NOOP);
    }

    private static LocatorDefinition cellAtRow(String row, int column) {
        return LocatorDefinition.jab("label", null, null, null, null, "table[Appointments]", null, null)
                .withCellText(row, String.valueOf(column));
    }

    // ---- a placeholder in row ----

    @Test
    void takesTheRowFromTheRunInput() {
        RunResult result = run(Map.of("wantedRow", "2"),
                TestSteps.step("Read the patient", ActionType.GET_TEXT, cellAtRow("${wantedRow}", 1))
                        .outputKey("patient").build());

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals("Alan Turing", result.outputs().get("patient"));
    }

    @Test
    void takesTheRowFromAnEarlierStepsOutput() {
        RunResult result = run(Map.of(),
                TestSteps.step("Read the row number held in the grid", ActionType.GET_TEXT,
                                cellAtRow("2", 2)).outputKey("target").build(),
                TestSteps.step("Read the patient in that row", ActionType.GET_TEXT,
                                cellAtRow("${target}", 1)).outputKey("patient").build());

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals("2", result.outputs().get("target"));
        assertEquals("Alan Turing", result.outputs().get("patient"),
                "the row number came out of the grid and went straight back into a locator");
    }

    /** A locator's name resolves too, which it never did before — only inputValue was substituted. */
    @Test
    void resolvesAPlaceholderInALocatorName() {
        RunResult result = run(Map.of("gridName", "Appointments"),
                TestSteps.step("Find the named grid", ActionType.GET_TEXT,
                                LocatorDefinition.jab("table", "${gridName}", NameMatch.CONTAINS))
                        .outputKey("grid").build());

        assertEquals(RunStatus.SUCCEEDED, result.status(),
                "before this, a locator name was never substituted and would look for a control "
                        + "literally called ${gridName}");
        assertEquals("Appointments", result.outputs().get("grid"));
    }

    /** A row that resolves to nothing fails the step rather than matching every row. */
    @Test
    void failsWhenARowPlaceholderResolvesToNothing() {
        RunResult result = run(Map.of(),
                TestSteps.step("Read the patient", ActionType.GET_TEXT, cellAtRow("${missing}", 1))
                        .outputKey("patient").build());

        assertEquals(RunStatus.FAILED, result.status());
        assertTrue(result.error().message().contains("not a number"), result.error().message());
        assertTrue(result.error().message().contains("${missing}"), result.error().message());
    }

    // ---- the loop counter ----

    @Test
    void countsTheExecutionsOfARepeatedStep() {
        // Read row ${step.iteration} each pass, bounded to four passes: rows 0 through 3.
        RunResult result = run(Map.of(),
                TestSteps.keyed("read", ActionType.GET_TEXT, cellAtRow("${step.iteration}", 1))
                        .maxExecutions(4).onLimit("done").outputKey("patient")
                        .onFound("read").build(),
                TestSteps.keyed("done", ActionType.GET_TEXT,
                        LocatorDefinition.jab("table", "Appointments", NameMatch.CONTAINS)).build());

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals("Grace Hopper", result.outputs().get("patient"),
                "the last pass read row 3, so the counter advanced every time");
        assertEquals(List.of("Patient", "Ada Lovelace", "Alan Turing", "Grace Hopper"),
                result.steps().stream()
                        .filter(r -> "read".equals(r.name()))
                        .map(StepReport::output)
                        .filter(o -> o != null)
                        .toList(),
                "one row per pass, in order, starting at row 0");
    }

    /** Both forms exist because placeholders do no arithmetic. */
    @Test
    void offersAOneBasedCountAsWellAsAZeroBasedIndex() {
        RunResult result = run(Map.of(),
                TestSteps.keyed("probe", ActionType.GET_TEXT,
                                LocatorDefinition.jab("table", "Appointments", NameMatch.CONTAINS))
                        .maxExecutions(3).onLimit("after").outputKey("seen").onFound("probe").build(),
                TestSteps.keyed("after", ActionType.KEYS).inputValue(
                        "iteration=${step.iteration} executions=${step.executions}").build());

        assertEquals(RunStatus.FAILED, result.status(),
                "no keyboard is configured, so the last step fails — the point is what it was asked to send");
        assertTrue(result.error().message().contains("iteration=0 executions=1"),
                "the 'after' step is on its own first pass: " + result.error().message());
    }

    /** A bot's own output wins, so the engine's variables cannot silently shadow one. */
    @Test
    void aBotsOwnOutputTakesPrecedenceOverACounter() {
        RunResult result = run(Map.of(),
                TestSteps.step("Claim the name", ActionType.GET_TEXT,
                                LocatorDefinition.jab("label", "09:00", NameMatch.EXACT))
                        .outputKey("step.iteration").build(),
                TestSteps.step("Read the patient", ActionType.GET_TEXT,
                        cellAtRow("${step.iteration}", 1)).outputKey("patient").build());

        assertEquals(RunStatus.FAILED, result.status(),
                "the output says 09:00, which is not a row number — and that is the bot's own doing");
        assertTrue(result.error().message().contains("09:00"), result.error().message());
    }
}
