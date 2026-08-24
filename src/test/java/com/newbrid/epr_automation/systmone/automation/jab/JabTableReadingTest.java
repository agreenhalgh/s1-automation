package com.newbrid.epr_automation.systmone.automation.jab;

import com.newbrid.epr_automation.systmone.automation.jab.FakeTree.Node;
import com.newbrid.epr_automation.systmone.common.dto.BotDefinition;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.dto.RetryPolicyDef;
import com.newbrid.epr_automation.systmone.common.dto.StepDefinition;
import com.newbrid.epr_automation.systmone.common.dto.TestSteps;
import com.newbrid.epr_automation.systmone.common.model.ActionType;
import com.newbrid.epr_automation.systmone.common.model.NameMatch;
import com.newbrid.epr_automation.systmone.common.model.RunStatus;
import com.newbrid.epr_automation.systmone.sdk.engine.BotEngine;
import com.newbrid.epr_automation.systmone.sdk.engine.KeyboardControl;
import com.newbrid.epr_automation.systmone.sdk.engine.RunListener;
import com.newbrid.epr_automation.systmone.sdk.engine.RunResult;
import com.newbrid.epr_automation.systmone.sdk.engine.ScreenshotSink;
import com.newbrid.epr_automation.systmone.sdk.engine.WindowControl;
import com.newbrid.epr_automation.systmone.sdk.locator.BackendRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The worked examples in {@code docs/BOT-DEFINITIONS.md} for reading data out of a table, run
 * against a grid whose cells exist only through the table API — the shape a Swing {@code JTable}
 * presents, where the tree shows a table with nothing in it.
 *
 * <p>Here so the documentation cannot drift: if an example stops working, this fails.</p>
 */
class JabTableReadingTest {

    private static final String[][] APPOINTMENTS = {
            {"Time", "Patient", "Status"},
            {"09:00", "Ada Lovelace", "Booked"},
            {"09:20", "Alan Turing", "Arrived"},
            {"09:40", "Grace Hopper", "Cancelled"},
    };

    /** A grid of {@link #APPOINTMENTS}, reachable only by row and column. */
    private static Node appointmentBook() {
        Node dialog = FakeTree.node("dialog", "Appointment Book", null, 0, 0, 900, 700, 0);
        dialog.add(FakeTree.node("table", "Appointments", null, 10, 100, 880, 300, 0)
                .asTable(APPOINTMENTS.length, APPOINTMENTS[0].length,
                        (row, column) -> FakeTree.node("label", APPOINTMENTS[row][column], null,
                                20 + column * 290, 130 + row * 26, 280, 24, column)));
        return dialog;
    }

    /** Records what it was asked to send, so a resolved placeholder can be seen arriving. */
    private static final class Keys implements KeyboardControl {
        final List<String> sent = new ArrayList<>();

        @Override public boolean isAvailable() { return true; }
        @Override public void keys(String combo) { sent.add(combo); }
    }

    private static LocatorDefinition theGrid() {
        return LocatorDefinition.jab("table", "Appointments", NameMatch.CONTAINS);
    }

    private static LocatorDefinition cell(int row, int column) {
        return LocatorDefinition.jab("label", null, null, null, null, "table[Appointments]", null, null)
                .withCell(row, column);
    }

    private RunResult run(Keys keyboard, StepDefinition... steps) {
        BackendRegistry backends = new BackendRegistry()
                .register(new JabBackend(FakeTree.bridge(appointmentBook()), null, "SystmOne"));
        BotDefinition bot = new BotDefinition("read-a-table", "Read a table", 1, "2024",
                List.of(steps), 0, RetryPolicyDef.none());
        return new BotEngine(backends, ScreenshotSink.NONE, WindowControl.UNAVAILABLE, keyboard)
                .run(bot, Map.of(), "run-table-docs", RunListener.NOOP);
    }

    // ---- one cell ----

    @Test
    void readsOneCellByRowAndColumn() {
        RunResult result = run(new Keys(),
                TestSteps.step("Read the patient in row 2", ActionType.GET_TEXT, cell(2, 1))
                        .outputKey("patient").build());

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals("Alan Turing", result.outputs().get("patient"));
    }

    /** A whole row is one step per column, each with its own output. */
    @Test
    void readsAWholeRowIntoSeparateOutputs() {
        RunResult result = run(new Keys(),
                TestSteps.step("Time", ActionType.GET_TEXT, cell(3, 0)).outputKey("time").build(),
                TestSteps.step("Patient", ActionType.GET_TEXT, cell(3, 1)).outputKey("patient").build(),
                TestSteps.step("Status", ActionType.GET_TEXT, cell(3, 2)).outputKey("status").build());

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals("09:40", result.outputs().get("time"));
        assertEquals("Grace Hopper", result.outputs().get("patient"));
        assertEquals("Cancelled", result.outputs().get("status"));
    }

    /** Row 0 is the header, so reading it is how a bot learns what the columns are called. */
    @Test
    void readsTheColumnHeadings() {
        RunResult result = run(new Keys(),
                TestSteps.step("First heading", ActionType.GET_TEXT, cell(0, 0)).outputKey("c0").build(),
                TestSteps.step("Second heading", ActionType.GET_TEXT, cell(0, 1)).outputKey("c1").build());

        assertEquals("Time", result.outputs().get("c0"));
        assertEquals("Patient", result.outputs().get("c1"));
    }

    // ---- the size ----

    @Test
    void readsTheGridsSizeIntoTwoOutputs() {
        RunResult result = run(new Keys(),
                TestSteps.step("How big is the grid?", ActionType.GET_TABLE_SIZE, theGrid())
                        .outputKey("grid").build());

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals("4", result.outputs().get("grid.rows"), "three appointments plus the header");
        assertEquals("3", result.outputs().get("grid.columns"));
    }

    /** The dotted keys are ordinary outputs, so they work anywhere a placeholder does. */
    @Test
    void aSizeOutputCanBeUsedAsAPlaceholder() {
        Keys keyboard = new Keys();

        RunResult result = run(keyboard,
                TestSteps.step("How big is the grid?", ActionType.GET_TABLE_SIZE, theGrid())
                        .outputKey("grid").build(),
                TestSteps.step("Type the count", ActionType.KEYS)
                        .inputValue("${grid.rows}").build());

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(List.of("4"), keyboard.sent, "the placeholder resolved before the keystrokes");
    }

    // ---- what the tree says about the same grid ----

    /** Worth pinning, because it is the reason GET_TABLE_SIZE exists. */
    @Test
    void countingTheTreeFindsNothingInThisGrid() {
        RunResult result = run(new Keys(),
                TestSteps.step("Count the rows in the tree", ActionType.GET_COUNT,
                                LocatorDefinition.jab("table row", null, null))
                        .outputKey("treeRows").build());

        assertEquals("0", result.outputs().get("treeRows"),
                "the cells are not children, so a walk finds no rows at all");
    }
}
