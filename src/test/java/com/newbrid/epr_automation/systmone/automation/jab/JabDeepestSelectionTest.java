package com.newbrid.epr_automation.systmone.automation.jab;

import com.newbrid.epr_automation.systmone.automation.jab.FakeTree.Node;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A click in the capture tool must land on the control, not on the thing the control sits in.
 *
 * <p>It reported the page tab instead, and there were two independent reasons a walk could stop
 * above the control. Both are pinned here, because both come back the moment someone reinstates
 * the "obvious" optimisation of trusting a container's own attributes.</p>
 */
class JabDeepestSelectionTest {

    /** dialog > page tab list > page tab > panel > [label, text], with the field at (110..310, 200..224). */
    private static Node tabbedForm(Node panel) {
        Node dialog = FakeTree.node("dialog", "Patient Registration", null, 0, 0, 900, 700, 0);
        Node tabs = FakeTree.node("page tab list", null, null, 0, 30, 900, 660, 0);
        Node tab = FakeTree.node("page tab", "Patient Details", null, 0, 60, 900, 630, 0);
        panel.add(FakeTree.node("label", "Surname", null, 20, 200, 80, 20, 0));
        panel.add(FakeTree.node("text", null, null, 110, 200, 200, 24, 1));
        tab.add(panel);
        tabs.add(tab);
        dialog.add(tabs);
        return dialog;
    }

    private static String selectedAt(Node root, int x, int y) {
        Optional<JabInspector.Inspection> found =
                new JabInspector(FakeTree.bridge(root), "SystmOne").inspect(x, y);
        return found.map(i -> i.element().role()).orElse("nothing");
    }

    @Test
    void selectsTheControlRatherThanTheTabItIsOn() {
        Node panel = FakeTree.node("panel", null, null, 0, 90, 900, 600, 0);

        assertEquals("text", selectedAt(tabbedForm(panel), 200, 210));
    }

    /**
     * The cause that produced the report. A {@code page tab} reports the bounds of its tab
     * <em>button</em>, and a viewport or scroll pane reports a clipped rectangle — so a container
     * whose rectangle does not cover its own children used to cut the walk off above them.
     */
    @Test
    void looksInsideAContainerWhoseBoundsDoNotCoverItsChildren() {
        // Bounds of the tab strip only: 25 pixels tall, nowhere near the field at y=210.
        Node panel = FakeTree.node("panel", null, null, 0, 30, 900, 25, 0);

        assertEquals("text", selectedAt(tabbedForm(panel), 200, 210),
                "a container that misreports its extent must cost reads, not the right answer");
    }

    /**
     * The other cause. A layout node that omits {@code showing} from its state set is making a
     * claim about itself, not about the control inside it.
     */
    @Test
    void looksInsideAContainerThatOmitsTheShowingState() {
        Node panel = FakeTree.node("panel", null, null, 0, 90, 900, 600, 0).hidden();

        assertEquals("text", selectedAt(tabbedForm(panel), 200, 210));
    }

    /** A control that says it is not on screen is still ruled out: its own state is about itself. */
    @Test
    void stillRulesOutAControlThatIsNotShowing() {
        Node dialog = FakeTree.node("dialog", "Patient Registration", null, 0, 0, 900, 700, 0);
        Node panel = FakeTree.node("panel", null, null, 0, 90, 900, 600, 0);
        panel.add(FakeTree.node("text", null, null, 110, 200, 200, 24, 0).hidden());
        dialog.add(panel);

        assertEquals("panel", selectedAt(dialog, 200, 210),
                "the container is the honest answer when the control itself is not there");
    }

    /** A control with no bounds cannot be clicked, so the container is again the answer. */
    @Test
    void stillRulesOutAControlWithNoBounds() {
        Node dialog = FakeTree.node("dialog", "Patient Registration", null, 0, 0, 900, 700, 0);
        Node panel = FakeTree.node("panel", null, null, 0, 90, 900, 600, 0);
        panel.add(FakeTree.node("text", null, null, 110, 200, 0, 0, 0));
        dialog.add(panel);

        assertEquals("panel", selectedAt(dialog, 200, 210));
    }

    /**
     * Depth decides, not area. A label drawn across its whole row is deeper than the row and is
     * the answer even though the row is not much bigger.
     */
    @Test
    void prefersDepthOverASmallerShallowerNode() {
        Node dialog = FakeTree.node("dialog", "Grid", null, 0, 0, 900, 700, 0);
        // A narrow sibling that also covers the point, one level shallower than the label.
        dialog.add(FakeTree.node("separator", null, null, 195, 200, 10, 24, 0));
        Node row = FakeTree.node("table row", null, null, 0, 200, 900, 24, 1);
        row.add(FakeTree.node("label", "Booked", null, 0, 200, 900, 24, 0));
        dialog.add(row);

        assertEquals("label", selectedAt(dialog, 200, 210),
                "the separator is smaller but shallower, and depth is what 'the control' means");
    }

    /** Between siblings at the same depth, the tighter one still wins. */
    @Test
    void breaksTiesAtEqualDepthByArea() {
        Node dialog = FakeTree.node("dialog", "Grid", null, 0, 0, 900, 700, 0);
        Node panel = FakeTree.node("panel", null, null, 0, 100, 900, 500, 0);
        panel.add(FakeTree.node("panel", "backdrop", null, 0, 100, 900, 500, 0));
        panel.add(FakeTree.node("check box", "Confirmed", null, 190, 200, 20, 20, 1));
        dialog.add(panel);

        assertEquals("check box", selectedAt(dialog, 200, 210));
    }
}
