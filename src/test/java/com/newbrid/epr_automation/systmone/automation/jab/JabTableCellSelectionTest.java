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
 * Clicking a table cell must select the cell, not the table.
 *
 * <p>The cause was coordinate spaces. A Swing {@code JTable} exposes each cell through a synthetic
 * accessible object, and those routinely report a rectangle measured from the table's own top-left
 * rather than from the screen. Taken at face value the cell sits near the top of the desktop, so a
 * click inside it looks like a click outside every cell — the tool answered with the table, and the
 * engine would have driven the mouse to the wrong place.</p>
 */
class JabTableCellSelectionTest {

    /** The table occupies (10,100)-(890,400). Row 1, column 1 covers (310..590, 160..186). */
    private static Node grid(boolean cellsMeasureFromTheTable) {
        Node dialog = FakeTree.node("dialog", "Appointment Book", null, 0, 0, 900, 700, 0);
        Node table = FakeTree.node("table", "Appointments", null, 10, 100, 880, 300, 0);
        for (int r = 0; r < 3; r++) {
            Node row = cellsMeasureFromTheTable
                    ? FakeTree.node("table row", null, null, 0, 30 + r * 30, 880, 30, r)
                    : FakeTree.node("table row", null, null, 10, 130 + r * 30, 880, 30, r);
            for (int c = 0; c < 3; c++) {
                row.add(cellsMeasureFromTheTable
                        ? FakeTree.node("label", "r" + r + "c" + c, null,
                                10 + c * 290, 30 + r * 30, 280, 26, c)
                        : FakeTree.node("label", "r" + r + "c" + c, null,
                                20 + c * 290, 130 + r * 30, 280, 26, c));
            }
            table.add(row);
        }
        dialog.add(table);
        return dialog;
    }

    private static JabInspector.Inspection at(Node root, int x, int y) {
        return new JabInspector(FakeTree.bridge(root), "SystmOne").inspect(x, y).orElseThrow();
    }

    @Test
    void selectsTheCellWhenCellsReportScreenCoordinates() {
        JabInspector.Inspection found = at(grid(false), 350, 170);

        assertEquals("label", found.element().role());
        assertEquals("r1c1", found.element().name());
    }

    /** The shape that produced the report. */
    @Test
    void selectsTheCellWhenCellsMeasureFromTheTable() {
        JabInspector.Inspection found = at(grid(true), 350, 170);

        assertEquals("label", found.element().role());
        assertEquals("r1c1", found.element().name(), "the table was answering instead of the cell");
    }

    /** The highlight has to be drawn where the cell really is, not where it says it is. */
    @Test
    void reportsTheCellsCorrectedBounds() {
        JabInspector.Inspection found = at(grid(true), 350, 170);

        assertEquals(310, found.element().x(), "the reported 10 plus the table's own x");
        assertEquals(160, found.element().y(), "the reported 30 plus the table's own y");
    }

    /** The chain still reads as a grid, so an author can see what they clicked into. */
    @Test
    void reportsTheRowAndTableAsAncestors() {
        JabInspector.Inspection found = at(grid(true), 350, 170);

        assertEquals("table row", found.ancestors().get(found.ancestors().size() - 1).role());
        assertTrue(found.ancestors().stream().anyMatch(a -> "table".equals(a.role())));
    }

    // ---- the same correction where it decides a click ----

    /**
     * The engine drives the mouse to a resolved element's bounds, so an uncorrected cell would be
     * clicked near the top of the desktop rather than in the grid.
     */
    @Test
    void resolvesACellToTheCoordinatesTheMouseShouldGoTo() {
        JabBackend jab = new JabBackend(FakeTree.bridge(grid(true)), null, "SystmOne");

        Optional<ElementHandle> hit = jab.resolve(
                LocatorDefinition.jab("label", "r1c1", NameMatch.EXACT), Duration.ZERO);

        assertTrue(hit.isPresent());
        assertEquals(310, hit.get().screenBounds().x);
        assertEquals(160, hit.get().screenBounds().y);
    }

    @Test
    void leavesCoordinatesAloneWhenTheyAreAlreadyAbsolute() {
        JabBackend jab = new JabBackend(FakeTree.bridge(grid(false)), null, "SystmOne");

        Optional<ElementHandle> hit = jab.resolve(
                LocatorDefinition.jab("label", "r1c1", NameMatch.EXACT), Duration.ZERO);

        assertTrue(hit.isPresent());
        assertEquals(20 + 290, hit.get().screenBounds().x);
        assertEquals(160, hit.get().screenBounds().y);
    }

    /**
     * A node that lands outside its parent under either reading is left alone: an overlay, a popup
     * or a stale rectangle is not a coordinate-space problem, and translating it would only move
     * the error somewhere else.
     */
    @Test
    void doesNotTranslateANodeThatFitsNeitherReading() {
        Node dialog = FakeTree.node("dialog", "Appointment Book", null, 0, 0, 900, 700, 0);
        Node panel = FakeTree.node("panel", null, null, 10, 100, 100, 100, 0);
        panel.add(FakeTree.node("label", "adrift", null, 5000, 5000, 50, 20, 0));
        dialog.add(panel);
        JabBackend jab = new JabBackend(FakeTree.bridge(dialog), null, "SystmOne");

        Optional<ElementHandle> hit = jab.resolve(
                LocatorDefinition.jab("label", "adrift", NameMatch.EXACT), Duration.ZERO);

        assertTrue(hit.isPresent());
        assertEquals(5000, hit.get().screenBounds().x, "reported as-is, wrong or not");
    }
}
