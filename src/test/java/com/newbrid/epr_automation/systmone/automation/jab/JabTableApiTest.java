package com.newbrid.epr_automation.systmone.automation.jab;

import com.newbrid.epr_automation.systmone.automation.jab.FakeTree.Node;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.model.NameMatch;
import com.newbrid.epr_automation.systmone.sdk.locator.ElementHandle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reaching the cells of a table that keeps them out of the accessibility tree.
 *
 * <p>A Swing {@code JTable} exposes no row or cell children at all: the tree shows a table with
 * nothing in it, however many rows the grid has. Walking cannot find those cells by any amount of
 * cleverness, so the table API is asked instead — {@code getAccessibleTableInfo} for the geometry
 * and {@code getAccessibleTableCellInfo} for one cell by row and column.</p>
 */
class JabTableApiTest {

    /** A 40x3 grid with no children in the tree; cells exist only through the table API. */
    private static Node gridWithNoChildren(int rows) {
        Node dialog = FakeTree.node("dialog", "Appointment Book", null, 0, 0, 900, 700, 0);
        Node table = FakeTree.node("table", "Appointments", null, 10, 100, 880, 500, 0)
                .asTable(rows, 3, (row, column) -> FakeTree.node("label",
                        "r" + row + "c" + column, null,
                        20 + column * 290, 130 + row * 26, 280, 24, column));
        dialog.add(table);
        return dialog;
    }

    private JabBackend backend(Node root) {
        return new JabBackend(FakeTree.bridge(root), null, "SystmOne");
    }

    private static LocatorDefinition cell(int row, int column) {
        return LocatorDefinition.jab("label", null, null, null, null, "table[Appointments]", null, null)
                .withCell(row, column);
    }

    // ---- cells the tree does not have ----

    @Test
    void resolvesACellThroughTheTableApiWhenTheTreeHasNone() {
        Optional<ElementHandle> hit = backend(gridWithNoChildren(40)).resolve(cell(7, 2), Duration.ZERO);

        assertTrue(hit.isPresent(), "no walk could have found this cell");
        assertEquals("JAB[label:r7c2]", hit.get().description());
    }

    @Test
    void placesTheCellWhereItReallyIs() {
        Optional<ElementHandle> hit = backend(gridWithNoChildren(40)).resolve(cell(7, 2), Duration.ZERO);

        assertEquals(20 + 2 * 290, hit.get().screenBounds().x);
        assertEquals(130 + 7 * 26, hit.get().screenBounds().y);
    }

    /** Addressed, not searched: asking for row 39 of 40 costs the same as row 0. */
    @Test
    void readsOnlyTheCellItWasAskedFor() {
        FakeTree.Counting bridge = FakeTree.counting(gridWithNoChildren(400));

        new JabBackend(bridge, null, "SystmOne").resolve(cell(399, 1), Duration.ZERO);

        assertTrue(bridge.reads() < 10,
                "one lookup, not a walk of 1200 cells: " + bridge.reads() + " reads");
    }

    @Test
    void findsNothingBeyondTheTablesOwnBounds() {
        JabBackend jab = backend(gridWithNoChildren(5));

        assertEquals(Optional.empty(), jab.resolve(cell(9, 0), Duration.ZERO));
        assertEquals(Optional.empty(), jab.resolve(cell(0, 5), Duration.ZERO));
    }

    /** The rest of the locator still applies: the cell is held to it like any walked node. */
    @Test
    void stillAppliesTheRestOfTheLocatorToTheCell() {
        JabBackend jab = backend(gridWithNoChildren(5));

        assertEquals(Optional.empty(), jab.resolve(
                        LocatorDefinition.jab("push button", null, null, null, null,
                                "table[Appointments]", null, null).withCell(1, 1), Duration.ZERO),
                "the cell is a label, so a locator asking for a button must not match it");
        assertTrue(jab.resolve(LocatorDefinition.jab("label", "r1c1", NameMatch.EXACT, null, null,
                "table[Appointments]", null, null).withCell(1, 1), Duration.ZERO).isPresent());
    }

    /** A cell handle is a reference like any other, and the ones not returned go back. */
    @Test
    void holdsOnlyTheCellItReturns() {
        FakeTree.Tracking bridge = FakeTree.tracking(gridWithNoChildren(20));
        JabBackend jab = new JabBackend(bridge, null, "SystmOne");

        try (ElementHandle handle = jab.resolve(cell(3, 1), Duration.ZERO).orElseThrow()) {
            assertEquals(1, bridge.leaked().size(), "leaked: " + bridge.leaked());
        }

        assertEquals(0, bridge.leaked().size());
        assertEquals(bridge.opened(), bridge.released());
    }

    @Test
    void holdsNothingWhenTheCellDoesNotMatch() {
        FakeTree.Tracking bridge = FakeTree.tracking(gridWithNoChildren(20));

        new JabBackend(bridge, null, "SystmOne").resolve(
                LocatorDefinition.jab("push button", null, null, null, null, "table[Appointments]",
                        null, null).withCell(3, 1), Duration.ZERO);

        assertEquals(0, bridge.leaked().size(), "leaked: " + bridge.leaked());
    }

    // ---- clicking a cell in the tool ----

    @Test
    void theToolSelectsACellThatIsNotInTheTree() {
        JabInspector.Inspection found = new JabInspector(FakeTree.bridge(gridWithNoChildren(40)),
                "SystmOne").inspect(20 + 290 + 5, 130 + 7 * 26 + 5).orElseThrow();

        assertEquals("label", found.element().role());
        assertEquals("r7c1", found.element().name(), "the table was answering instead of the cell");
    }

    @Test
    void theToolReportsTheCellsOwnNeighboursAndTable() {
        JabInspector.Inspection found = new JabInspector(FakeTree.bridge(gridWithNoChildren(40)),
                "SystmOne").inspect(20 + 290 + 5, 130 + 7 * 26 + 5).orElseThrow();

        assertEquals("r7c0", found.previousSibling().name(), "the cell to its left");
        assertEquals("r7c2", found.followingSibling().name(), "and to its right");
        assertEquals("table", found.ancestors().get(found.ancestors().size() - 1).role());
    }

    /**
     * Rows are found by bisection, so a click in a huge grid costs a dozen probes rather than a
     * native call per row — the difference between answering and appearing to hang.
     */
    @Test
    void findsTheRowWithoutScanningTheWholeTable() {
        // Thin rows so 400 of them still fit inside the table's own bounds, as a real grid's do
        // once it is scrolled: the point of the test is the number of probes, not the pixels.
        Node dialog = FakeTree.node("dialog", "Appointment Book", null, 0, 0, 900, 700, 0);
        dialog.add(FakeTree.node("table", "Appointments", null, 10, 100, 880, 500, 0)
                .asTable(400, 3, (row, column) -> FakeTree.node("label",
                        "r" + row + "c" + column, null,
                        20 + column * 290, 110 + row, 280, 1, column)));
        FakeTree.Counting bridge = FakeTree.counting(dialog);

        JabInspector.Inspection found = new JabInspector(bridge, "SystmOne")
                .inspect(20 + 5, 110 + 350).orElseThrow();

        assertEquals("r350c0", found.element().name());
        assertTrue(bridge.reads() < 40, "bisection, not a scan of 400 rows: " + bridge.reads());
    }

    @Test
    void answersWithTheTableWhenThePointIsInNoCell() {
        JabInspector.Inspection found = new JabInspector(FakeTree.bridge(gridWithNoChildren(5)),
                "SystmOne").inspect(880, 590).orElseThrow();

        assertEquals("table", found.element().role(), "inside the table but below its last row");
    }

    @Test
    void theToolHoldsNoCellHandlesAfterwards() {
        FakeTree.Tracking bridge = FakeTree.tracking(gridWithNoChildren(40));

        new JabInspector(bridge, "SystmOne").inspect(20 + 5, 130 + 7 * 26 + 5).orElseThrow();

        assertEquals(0, bridge.leaked().size(), "leaked: " + bridge.leaked());
        assertEquals(bridge.opened(), bridge.released());
    }

    // ---- counts from the model rather than the tree ----

    @Test
    void reportsTheTablesRealSizeRatherThanWhatTheTreeExposes() {
        JabBackend jab = backend(gridWithNoChildren(4_000));

        TableGeometry size = jab.tableGeometry(
                LocatorDefinition.jab("table", "Appointments", NameMatch.EXACT), Duration.ZERO);

        assertEquals(4_000, size.rows(), "counting the tree would have said nothing at all");
        assertEquals(3, size.columns());
    }

    @Test
    void countingTheTreeStillFindsNothingInSuchATable() {
        JabBackend jab = backend(gridWithNoChildren(4_000));

        assertEquals(0, jab.count(LocatorDefinition.jab("table row", null, null), Duration.ZERO),
                "which is exactly why the table API is needed, and worth stating");
    }

    @Test
    void reportsNoGeometryForSomethingThatIsNotATable() {
        Node dialog = FakeTree.node("dialog", "Appointment Book", null, 0, 0, 900, 700, 0);
        dialog.add(FakeTree.node("panel", "Filters", null, 10, 10, 200, 50, 0));
        JabBackend jab = backend(dialog);

        assertNull(jab.tableGeometry(
                LocatorDefinition.jab("panel", "Filters", NameMatch.EXACT), Duration.ZERO));
    }

    /** A grid that does expose its rows is walked as before — the API is not consulted. */
    @Test
    void leavesAWalkableGridAlone() {
        Node dialog = FakeTree.node("dialog", "Appointment Book", null, 0, 0, 900, 700, 0);
        Node table = FakeTree.node("table", "Appointments", null, 10, 100, 880, 300, 0);
        Node row = FakeTree.node("table row", null, null, 10, 130, 880, 26, 0);
        row.add(FakeTree.node("label", "walked", null, 20, 130, 280, 24, 0));
        table.add(row);
        dialog.add(table);

        Optional<ElementHandle> hit = backend(dialog).resolve(cell(0, 0), Duration.ZERO);

        assertTrue(hit.isPresent());
        assertEquals("JAB[label:walked]", hit.get().description(),
                "found by walking, since the tree has it");
    }
}
