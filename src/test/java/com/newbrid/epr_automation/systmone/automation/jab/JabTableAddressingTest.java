package com.newbrid.epr_automation.systmone.automation.jab;

import com.newbrid.epr_automation.systmone.automation.jab.FakeTree.Node;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.model.NameMatch;
import com.newbrid.epr_automation.systmone.sdk.locator.ElementHandle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Addressing a table cell by row and column, and counting rows — both through the engine's own
 * traversal, since both are read from the ancestor chain it maintains rather than from a table API.
 */
class JabTableAddressingTest {

    private static final String[] HEADERS = {"Time", "Patient", "Status"};

    /**
     * dialog > table > table row* > label. Row 0 is the header, as most grids have it, so the
     * first appointment is row 1.
     */
    private static Node appointments(int dataRows) {
        Node dialog = FakeTree.node("dialog", "Appointment Book", null, 0, 0, 900, 700, 0);
        Node table = FakeTree.node("table", "Appointments", null, 10, 40, 880, 600, 0);
        table.add(row(0, HEADERS));
        for (int r = 1; r <= dataRows; r++) {
            table.add(row(r, new String[]{"09:" + (r < 10 ? "0" + r : r), "Patient " + r, "Booked"}));
        }
        dialog.add(table);
        return dialog;
    }

    private static Node row(int index, String[] cells) {
        Node row = FakeTree.node("table row", null, null, 10, 60 + index * 24, 880, 24, index);
        for (int c = 0; c < cells.length; c++) {
            row.add(FakeTree.node("label", cells[c], null, 20 + c * 250, 60 + index * 24, 240, 20, c));
        }
        return row;
    }

    private JabBackend backend(Node... windows) {
        return new JabBackend(FakeTree.bridge(windows), null, "SystmOne");
    }

    private static LocatorDefinition cell(int row, int column) {
        return LocatorDefinition.jab("label", null, null, null, null, "table[Appointments]", null, null)
                .withCell(row, column);
    }

    // ---- addressing a cell ----

    @Test
    void resolvesTheCellAtARowAndColumn() {
        JabBackend jab = backend(appointments(5));

        Optional<ElementHandle> hit = jab.resolve(cell(3, 1), Duration.ZERO);

        assertTrue(hit.isPresent());
        assertEquals(60 + 3 * 24, hit.get().screenBounds().y, "the third data row after the header");
        assertEquals(20 + 250, hit.get().screenBounds().x, "the Patient column");
    }

    @Test
    void addressesTheHeaderAsRowZero() {
        JabBackend jab = backend(appointments(5));

        Optional<ElementHandle> hit = jab.resolve(cell(0, 2), Duration.ZERO);

        assertTrue(hit.isPresent());
        assertEquals(60, hit.get().screenBounds().y, "row 0 is the header row");
    }

    /** A row on its own addresses the whole row, whatever is in it. */
    @Test
    void addressesAWholeRowWithoutAColumn() {
        JabBackend jab = backend(appointments(5));

        Optional<ElementHandle> hit = jab.resolve(
                LocatorDefinition.jab("table row", null, null).withCell(2, null), Duration.ZERO);

        assertTrue(hit.isPresent());
        assertEquals(60 + 2 * 24, hit.get().screenBounds().y);
    }

    @Test
    void findsNothingBeyondTheEndOfTheTable() {
        JabBackend jab = backend(appointments(3));

        assertEquals(Optional.empty(), jab.resolve(cell(9, 0), Duration.ZERO));
        assertEquals(Optional.empty(), jab.resolve(cell(1, 7), Duration.ZERO));
    }

    /**
     * The column is the position of whatever sits directly under the row, not of the element
     * itself — a cell that wraps its contents puts the element a level deeper.
     */
    @Test
    void countsTheColumnFromTheRowNotFromTheElementsOwnParent() {
        Node dialog = FakeTree.node("dialog", "Appointment Book", null, 0, 0, 900, 700, 0);
        Node table = FakeTree.node("table", "Appointments", null, 10, 40, 880, 600, 0);
        Node row = FakeTree.node("table row", null, null, 10, 60, 880, 24, 0);
        for (int c = 0; c < 3; c++) {
            Node wrapper = FakeTree.node("panel", null, null, 20 + c * 250, 60, 240, 20, c);
            // The only child of its wrapper, so its own indexInParent is 0 in every column.
            wrapper.add(FakeTree.node("label", "Cell " + c, null, 20 + c * 250, 60, 240, 20, 0));
            row.add(wrapper);
        }
        table.add(row);
        dialog.add(table);
        JabBackend jab = backend(dialog);

        Optional<ElementHandle> hit = jab.resolve(cell(0, 2), Duration.ZERO);

        assertTrue(hit.isPresent(), "column 2 is the third wrapper, whatever the label's own index is");
        assertEquals(20 + 2 * 250, hit.get().screenBounds().x);
    }

    /** A grid with no row nodes cannot be addressed this way, and says so by matching nothing. */
    @Test
    void matchesNothingInAFlatTableWithNoRowNodes() {
        Node dialog = FakeTree.node("dialog", "Appointment Book", null, 0, 0, 900, 700, 0);
        Node table = FakeTree.node("table", "Appointments", null, 10, 40, 880, 600, 0);
        for (int i = 0; i < 9; i++) {
            table.add(FakeTree.node("label", "Cell " + i, null, 20, 60 + i * 24, 240, 20, i));
        }
        dialog.add(table);
        JabBackend jab = backend(dialog);

        assertEquals(Optional.empty(), jab.resolve(cell(1, 1), Duration.ZERO),
                "no row nodes, so no row numbers — the flat shape needs the table API");
    }

    // ---- counting ----

    @Test
    void countsTheRowsOfATable() {
        JabBackend jab = backend(appointments(12));

        int rows = jab.count(LocatorDefinition.jab("table row", null, null, null, null,
                "table[Appointments]", null, null), Duration.ZERO);

        assertEquals(13, rows, "twelve appointments plus the header row");
    }

    @Test
    void countsTheColumnsFromTheHeaderRow() {
        JabBackend jab = backend(appointments(12));

        int columns = jab.count(LocatorDefinition.jab("label", null, null, null, null,
                "table[Appointments]", null, null).withCell(0, null), Duration.ZERO);

        assertEquals(HEADERS.length, columns, "the cells of row 0");
    }

    @Test
    void countsZeroForAnEmptyGridRatherThanFailing() {
        JabBackend jab = backend(appointments(0));

        int rows = jab.count(LocatorDefinition.jab("table row", null, null, null, null,
                "table[Appointments]", null, null).withCell(1, null), Duration.ZERO);

        assertEquals(0, rows, "a grid with no data rows is a fact, not a failure");
    }

    /** Counting is the one thing several matches do not spoil. */
    @Test
    void countsWhereResolveWouldRefuseAsAmbiguous() {
        JabBackend jab = backend(appointments(4));
        LocatorDefinition everyStatus = LocatorDefinition.jab("label", "Booked", NameMatch.EXACT);

        assertEquals(Optional.empty(), jab.resolve(everyStatus, Duration.ZERO),
                "four matches, so the engine refuses to act");
        assertEquals(4, jab.count(everyStatus, Duration.ZERO), "and counting them is the point");
    }

    /** The count walk holds no references either. */
    @Test
    void countingHoldsNoHandlesAfterwards() {
        FakeTree.Tracking bridge = FakeTree.tracking(appointments(6));

        new JabBackend(bridge, null, "SystmOne").count(
                LocatorDefinition.jab("table row", null, null), Duration.ZERO);

        assertEquals(0, bridge.leaked().size(), "leaked: " + bridge.leaked());
        assertEquals(bridge.opened(), bridge.released());
    }
}
