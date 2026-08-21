package com.newbrid.epr_automation.systmone.automation.jab;

import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.automation.jab.FakeTree.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The element-under-the-point descent and the locator it proposes (FR-TOOL-03), driven
 * through a scripted accessibility tree so the logic is tested without Windows, SystmOne or
 * the Access Bridge.
 */
class JabInspectorTest {

    /** A window with a toolbar button, and a table of rows whose cells share a role. */
    private static Node systmOne() {
        Node window = FakeTree.node("frame", "SystmOne", null, 0, 0, 1920, 1080, 0);
        Node toolbar = FakeTree.node("tool bar", null, null, 0, 0, 1920, 40, 0);
        toolbar.add(FakeTree.node("push button", "Patient", "Open a patient record", 10, 5, 90, 30, 0));
        toolbar.add(FakeTree.node("push button", "Appointments", null, 110, 5, 130, 30, 1));
        window.add(toolbar);

        Node table = FakeTree.node("table", "Appointments", null, 0, 100, 1200, 400, 1);
        for (int row = 0; row < 3; row++) {
            Node line = FakeTree.node("table row", null, null, 0, 100 + row * 30, 1200, 30, row);
            line.add(FakeTree.node("label", "Select", null, 20, 105 + row * 30, 60, 20, 0));
            table.add(line);
        }
        window.add(table);
        return window;
    }

    /** The window behind, and a modal over it whose buttons are named the same. */
    private static Node confirmDialog() {
        Node dialog = FakeTree.node("dialog", "Confirm deletion of Mr Smith", null, 600, 400, 400, 200, 0);
        dialog.add(FakeTree.node("push button", "Cancel", null, 620, 540, 80, 30, 0));
        dialog.add(FakeTree.node("push button", "Delete", null, 720, 540, 80, 30, 1));
        return dialog;
    }

    private static Node withCancelButton(Node window) {
        window.add(FakeTree.node("push button", "Cancel", null, 1500, 900, 80, 30, 2));
        return window;
    }

    private final JabInspector inspector = new JabInspector(FakeTree.bridge(systmOne()), "SystmOne");

    // ---- descent ----

    @Test
    void findsTheDeepestElementUnderThePointRatherThanItsContainer() {
        JabInspector.Inspection found = inspector.inspect(50, 20).orElseThrow();

        assertEquals("push button", found.element().role(),
                "the window and the toolbar also contain this point; the control is the answer");
        assertEquals("Patient", found.element().name());
    }

    @Test
    void reportsTheContainersAboveTheElementOutermostFirst() {
        JabInspector.Inspection found = inspector.inspect(50, 20).orElseThrow();

        assertEquals(List.of("frame", "tool bar"),
                found.ancestors().stream().map(a -> a.role()).toList());
    }

    /**
     * Empty space inside the window is not "nothing": the frame does contain the point, and
     * saying so lets the author see they clicked the background rather than a control.
     */
    @Test
    void fallsBackToTheWindowWhenThePointIsOnNoControl() {
        JabInspector.Inspection found = inspector.inspect(1900, 1000).orElseThrow();

        assertEquals("frame", found.element().role());
        assertTrue(found.ancestors().isEmpty(), "the window is the outermost node, so nothing is above it");
    }

    @Test
    void findsNothingOutsideEveryWindow() {
        assertEquals(Optional.empty(), inspector.inspect(3000, 2000));
    }

    // ---- the suggested locator ----

    @Test
    void suggestsRoleAndNameWhenThatIsEnoughToBeUnique() {
        JabInspector.Inspection found = inspector.inspect(50, 20).orElseThrow();
        LocatorDefinition locator = found.suggested();

        assertNotNull(locator);
        assertEquals("push button", locator.role());
        assertEquals("Patient", locator.name());
        assertEquals(1, found.matchCount(), "one Patient button exists, so the locator is usable");
        assertNull(locator.index(), "index is positional and brittle — not added when nothing needs it");
    }

    /**
     * The three rows hold identical "Select" labels. The engine refuses to act on a locator
     * that matches several elements, so the tool has to say so rather than return the first.
     */
    @Test
    void addsIndexAndStillReportsTheRealCountWhenSiblingsAreIdentical() {
        JabInspector.Inspection found = inspector.inspect(40, 140).orElseThrow();

        assertEquals("label", found.element().role());
        assertEquals("Select", found.element().name());
        assertNotNull(found.suggested().index(),
                "role+name matches all three rows, so the ladder falls back to position");
        // Every row's label is index 0 within its own row, so even index does not separate them.
        assertTrue(found.matchCount() > 1,
                "must report the ambiguity the engine would hit, not hide it behind a suggestion");
    }

    @Test
    void suggestsNothingForAnElementWithNoDistinguishingAttributes() {
        Node bare = FakeTree.node("frame", "SystmOne", null, 0, 0, 200, 200, 0);
        bare.add(FakeTree.node(null, null, null, 10, 10, 50, 50, 0));
        JabInspector plain = new JabInspector(FakeTree.bridge(bare), "SystmOne");

        JabInspector.Inspection found = plain.inspect(20, 20).orElseThrow();

        assertNull(found.suggested(), "nothing to write a locator from — the page must offer a template instead");
    }

    /** A locator is only worth suggesting if the engine's own predicate agrees it matches. */
    @Test
    void theSuggestedLocatorMatchesTheElementItWasBuiltFrom() {
        JabInspector.Inspection found = inspector.inspect(50, 20).orElseThrow();

        AccessibleInfo element = new AccessibleInfo(found.element().role(), found.element().name(),
                found.element().description(), found.element().states(),
                found.element().x(), found.element().y(), found.element().width(),
                found.element().height(), found.element().childCount(), found.element().indexInParent());

        assertTrue(JabMatching.matches(element, List.of(), found.suggested()));
    }

    // ---- neighbours ----

    /** dialog > panel > [label Surname, text, label Forename, text] — a form of unlabelled fields. */
    private static Node patientForm() {
        Node dialog = FakeTree.node("dialog", "Patient Registration", null, 0, 0, 800, 600, 0);
        Node panel = FakeTree.node("panel", null, null, 10, 40, 780, 500, 0);
        panel.add(FakeTree.node("label", "Surname", null, 20, 60, 80, 20, 0));
        panel.add(FakeTree.node("text", null, null, 110, 60, 200, 24, 1));
        panel.add(FakeTree.node("label", "Forename", null, 20, 100, 80, 20, 2));
        panel.add(FakeTree.node("text", null, null, 110, 100, 200, 24, 3));
        dialog.add(panel);
        return dialog;
    }

    @Test
    void reportsTheElementsImmediateNeighbours() {
        JabInspector form = new JabInspector(FakeTree.bridge(patientForm()), "SystmOne");

        JabInspector.Inspection found = form.inspect(150, 70).orElseThrow();

        assertEquals("text", found.element().role());
        assertEquals("Surname", found.previousSibling().name());
        assertEquals("label", found.previousSibling().role());
        assertEquals("Forename", found.followingSibling().name());
    }

    @Test
    void reportsNoNeighboursForAnOnlyChild() {
        Node window = FakeTree.node("frame", "SystmOne", null, 0, 0, 400, 400, 0);
        window.add(FakeTree.node("text", "Only", null, 10, 10, 100, 24, 0));
        JabInspector lonely = new JabInspector(FakeTree.bridge(window), "SystmOne");

        JabInspector.Inspection found = lonely.inspect(20, 20).orElseThrow();

        assertNull(found.previousSibling());
        assertNull(found.followingSibling());
    }

    /**
     * The case sibling selectors exist for. Two unlabelled fields of the same role: nothing about
     * either one distinguishes it, and the label beside it does.
     */
    @Test
    void suggestsTheLabelBesideAnUnlabelledField() {
        JabInspector form = new JabInspector(FakeTree.bridge(patientForm()), "SystmOne");

        JabInspector.Inspection found = form.inspect(150, 70).orElseThrow();

        assertEquals("label[Surname]", found.suggested().previousSibling());
        assertNull(found.suggested().index(),
                "a neighbour describes the form as an author reads it; index describes today's layout");
        assertEquals(1, found.matchCount(), "which makes it usable as it stands");
    }

    @Test
    void suggestsTheOtherFieldsOwnLabel() {
        JabInspector form = new JabInspector(FakeTree.bridge(patientForm()), "SystmOne");

        JabInspector.Inspection found = form.inspect(150, 110).orElseThrow();

        assertEquals("label[Forename]", found.suggested().previousSibling());
        assertEquals(1, found.matchCount());
    }

    /**
     * When the element before is anonymous, the one after can still do the job — a bare role is a
     * weaker selector than role[name] but it is a real constraint, and it beats a position.
     */
    @Test
    void usesTheFollowingNeighbourWhenThePrecedingOneIsAnonymous() {
        Node window = FakeTree.node("frame", "SystmOne", null, 0, 0, 400, 400, 0);
        Node panel = FakeTree.node("panel", null, null, 0, 0, 400, 400, 0);
        panel.add(FakeTree.node(null, null, null, 10, 10, 50, 20, 0));
        panel.add(FakeTree.node("text", null, null, 10, 40, 100, 24, 1));
        panel.add(FakeTree.node("text", null, null, 10, 70, 100, 24, 2));
        window.add(panel);
        JabInspector anonymous = new JabInspector(FakeTree.bridge(window), "SystmOne");

        JabInspector.Inspection found = anonymous.inspect(50, 50).orElseThrow();

        assertNull(found.suggested().previousSibling(),
                "the element before it has neither role nor name, so it distinguishes nothing");
        assertEquals("text", found.suggested().followingSibling(),
                "the field after it is a text, and the other field has nothing after it");
        assertEquals(1, found.matchCount());
    }

    /** With nothing nameable on either side, position is all that is left. */
    @Test
    void fallsBackToIndexWhenNeitherNeighbourCanBeNamed() {
        Node window = FakeTree.node("frame", "SystmOne", null, 0, 0, 400, 400, 0);
        Node panel = FakeTree.node("panel", null, null, 0, 0, 400, 400, 0);
        panel.add(FakeTree.node(null, null, null, 10, 10, 50, 20, 0));
        panel.add(FakeTree.node("text", null, null, 10, 40, 100, 24, 1));
        window.add(panel);
        Node second = FakeTree.node("panel", null, null, 0, 200, 400, 200, 1);
        second.add(FakeTree.node(null, null, null, 10, 210, 50, 20, 0));
        second.add(FakeTree.node("text", null, null, 10, 240, 100, 24, 1));
        window.add(second);
        JabInspector anonymous = new JabInspector(FakeTree.bridge(window), "SystmOne");

        JabInspector.Inspection found = anonymous.inspect(50, 50).orElseThrow();

        assertNull(found.suggested().previousSibling());
        assertNull(found.suggested().followingSibling(), "nothing follows it either");
        assertEquals(1, found.suggested().index(), "position is what is left");
        assertEquals(2, found.matchCount(),
                "and it does not even work: the other panel holds the same shape, which is the "
                        + "honest answer rather than a suggestion that looks fine");
    }

    /** A named control still gets the simplest thing that works, not a neighbour. */
    @Test
    void doesNotReachForANeighbourWhenTheElementNamesItself() {
        JabInspector.Inspection found = inspector.inspect(50, 20).orElseThrow();

        assertNull(found.suggested().previousSibling());
        assertEquals("Patient", found.suggested().name());
    }

    // ---- modal dialogs ----

    /**
     * The window behind a modal stays in the tree, rendered and matchable, and its buttons are
     * routinely named the same. An unscoped locator hits both, which the engine treats as
     * ambiguous, so the suggestion has to name the window the control is in.
     */
    @Test
    void scopesTheLocatorToTheDialogWhenTheWindowBehindHasTheSameControl() {
        JabInspector withModal = new JabInspector(
                FakeTree.bridge(withCancelButton(systmOne()), confirmDialog()), "SystmOne");

        JabInspector.Inspection found = withModal.inspect(650, 550).orElseThrow();

        assertEquals("push button", found.element().role());
        assertEquals("Cancel", found.element().name());
        assertEquals("dialog[Confirm deletion of Mr Smith]", found.suggested().ancestorPath(),
                "role+name alone matches the Cancel button in the window behind as well");
        assertEquals(1, found.matchCount(), "scoped to the dialog, it identifies one element");
    }

    @Test
    void reportsTheDialogAsTheElementsWindow() {
        JabInspector withModal = new JabInspector(
                FakeTree.bridge(withCancelButton(systmOne()), confirmDialog()), "SystmOne");

        JabInspector.Inspection found = withModal.inspect(650, 550).orElseThrow();

        assertEquals("dialog", found.ancestors().get(0).role());
        assertEquals("Confirm deletion of Mr Smith", found.ancestors().get(0).name());
    }

    /** Scoping is specificity, and specificity is only added when something needs it. */
    @Test
    void leavesTheLocatorUnscopedWhenOnlyOneWindowIsOpen() {
        JabInspector.Inspection found = inspector.inspect(50, 20).orElseThrow();

        assertNull(found.suggested().ancestorPath(),
                "a window title that could gain a patient name is not worth pinning to for nothing");
    }

    // ---- the focused element ----

    @Test
    void findsTheFocusedElementWithoutCoordinates() {
        Node window = FakeTree.node("frame", "SystmOne", null, 0, 0, 1920, 1080, 0);
        Node panel = FakeTree.node("panel", null, null, 0, 40, 800, 400, 0);
        panel.add(FakeTree.node("text", "NHS Number", null, 20, 60, 200, 24, 0).focused());
        panel.add(FakeTree.node("text", "Surname", null, 20, 100, 200, 24, 1));
        window.add(panel);
        JabInspector withFocus = new JabInspector(FakeTree.bridge(window), "SystmOne");

        JabInspector.Inspection found = withFocus.focused().orElseThrow();

        assertEquals("text", found.element().role());
        assertEquals("NHS Number", found.element().name());
        assertEquals(1, found.matchCount(), "role+name identifies it, so it is usable as it stands");
    }

    /**
     * A focused container and the focused control inside it both carry the state. The control is
     * the answer — it is the thing with the caret in it.
     */
    @Test
    void prefersTheDeepestFocusedNode() {
        Node window = FakeTree.node("frame", "SystmOne", null, 0, 0, 1920, 1080, 0).focused();
        Node panel = FakeTree.node("panel", "Details", null, 0, 40, 800, 400, 0).focused();
        panel.add(FakeTree.node("text", "NHS Number", null, 20, 60, 200, 24, 0).focused());
        window.add(panel);
        JabInspector withFocus = new JabInspector(FakeTree.bridge(window), "SystmOne");

        JabInspector.Inspection found = withFocus.focused().orElseThrow();

        assertEquals("text", found.element().role());
        assertEquals(List.of("frame", "panel"),
                found.ancestors().stream().map(a -> a.role()).toList());
    }

    /** A window with nothing focused has nothing to report, and says so rather than guessing. */
    @Test
    void findsNothingWhenNoElementHoldsFocus() {
        assertEquals(Optional.empty(), inspector.focused());
    }

    /** Focus is found in a modal too, and the suggestion is scoped to it like any other. */
    @Test
    void findsFocusInsideAModalDialog() {
        Node dialog = FakeTree.node("dialog", "Confirm deletion of Mr Smith", null, 600, 400, 400, 200, 0);
        dialog.add(FakeTree.node("push button", "Cancel", null, 620, 540, 80, 30, 0).focused());
        JabInspector withModal = new JabInspector(
                FakeTree.bridge(withCancelButton(systmOne()), dialog), "SystmOne");

        JabInspector.Inspection found = withModal.focused().orElseThrow();

        assertEquals("Cancel", found.element().name());
        assertEquals("dialog[Confirm deletion of Mr Smith]", found.suggested().ancestorPath(),
                "the window behind holds a Cancel button too, so the scope is what makes it unique");
    }

    /**
     * The ambiguity warning has to describe the locator, not just its name: an attribute locator
     * identified by role and ancestorPath has no name at all, and "locator null matched 2
     * elements" was the message an author got for exactly that shape.
     */
    @Test
    void describesEveryFieldALocatorSets() {
        LocatorDefinition scoped = LocatorDefinition.jab("text", null, null, null, 1,
                "dialog[Patient Registration] > page tab[Patient Details]");

        String described = scoped.describe();

        assertTrue(described.contains("role=\"text\""), described);
        assertTrue(described.contains("index=1"), described);
        assertTrue(described.contains("ancestorPath=\"dialog[Patient Registration] > page tab[Patient Details]\""),
                described);
        assertFalse(described.contains("name="), "a field that is not set must not appear: " + described);
    }

    @Test
    void reportsUnavailableRatherThanFailingWhenTheBridgeIsAbsent() {
        JabInspector none = new JabInspector(new NullAccessBridge("no Access Bridge on this host"), "SystmOne");

        assertEquals(Optional.empty(), none.inspect(10, 10));
    }
}
