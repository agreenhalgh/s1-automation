package com.newbrid.epr_automation.systmone.automation.jab;

import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The element-under-the-point descent and the locator it proposes (FR-TOOL-03), driven
 * through a scripted accessibility tree so the logic is tested without Windows, SystmOne or
 * the Access Bridge.
 */
class JabInspectorTest {

    // ---- a tree that answers like the bridge does ----

    /** One node of a fake application. Children are addressed by index, as JAB addresses them. */
    private static final class Node {
        final AccessibleInfo info;
        final List<Node> children = new ArrayList<>();

        Node(String role, String name, String description, int x, int y, int w, int h, int indexInParent) {
            this.info = new AccessibleInfo(role, name, description, "enabled,visible",
                    x, y, w, h, 0, indexInParent);
        }

        Node add(Node child) {
            children.add(child);
            return this;
        }

        AccessibleInfo info() {
            // childCount has to reflect the children actually attached, the way the real
            // struct does — the descent trusts it to know how far to walk.
            return new AccessibleInfo(info.role(), info.name(), info.description(), info.states(),
                    info.x(), info.y(), info.width(), info.height(), children.size(), info.indexInParent());
        }
    }

    /**
     * Serves {@link Node} trees through the bridge interface; handles are indices into a list.
     * Several roots stand for several top-level windows of one application — which is what a
     * modal dialog is, and the case the real bridge reports the same way.
     */
    private static final class FakeBridge implements AccessBridge {
        private final List<Node> handles = new ArrayList<>();
        private final List<Node> windows;

        FakeBridge(Node... windows) {
            this.windows = List.of(windows);
            this.windows.forEach(this::handleFor);
        }

        private long handleFor(Node node) {
            int existing = handles.indexOf(node);
            if (existing >= 0) return existing;
            handles.add(node);
            return handles.size() - 1;
        }

        @Override public boolean isAvailable() { return true; }

        @Override public List<AcRef> roots(String windowTitleContains) {
            return windows.stream().map(w -> new AcRef(1, handleFor(w))).toList();
        }
        @Override public AccessibleInfo info(AcRef ref) { return handles.get((int) ref.ac()).info(); }

        @Override public AcRef child(AcRef ref, int index) {
            List<Node> children = handles.get((int) ref.ac()).children;
            if (index < 0 || index >= children.size()) return null;
            return new AcRef(ref.vmId(), handleFor(children.get(index)));
        }

        @Override public boolean doAction(AcRef ref, String action) { return false; }
        @Override public boolean setText(AcRef ref, String text) { return false; }
        @Override public String getText(AcRef ref) { return null; }
        @Override public void release(AcRef ref) {}
    }

    /** A window with a toolbar button, and a table of rows whose cells share a role. */
    private static Node systmOne() {
        Node window = new Node("frame", "SystmOne", null, 0, 0, 1920, 1080, 0);
        Node toolbar = new Node("tool bar", null, null, 0, 0, 1920, 40, 0);
        toolbar.add(new Node("push button", "Patient", "Open a patient record", 10, 5, 90, 30, 0));
        toolbar.add(new Node("push button", "Appointments", null, 110, 5, 130, 30, 1));
        window.add(toolbar);

        Node table = new Node("table", "Appointments", null, 0, 100, 1200, 400, 1);
        for (int row = 0; row < 3; row++) {
            Node line = new Node("table row", null, null, 0, 100 + row * 30, 1200, 30, row);
            line.add(new Node("label", "Select", null, 20, 105 + row * 30, 60, 20, 0));
            table.add(line);
        }
        window.add(table);
        return window;
    }

    /** The window behind, and a modal over it whose buttons are named the same. */
    private static Node confirmDialog() {
        Node dialog = new Node("dialog", "Confirm deletion of Mr Smith", null, 600, 400, 400, 200, 0);
        dialog.add(new Node("push button", "Cancel", null, 620, 540, 80, 30, 0));
        dialog.add(new Node("push button", "Delete", null, 720, 540, 80, 30, 1));
        return dialog;
    }

    private static Node withCancelButton(Node window) {
        window.add(new Node("push button", "Cancel", null, 1500, 900, 80, 30, 2));
        return window;
    }

    private final JabInspector inspector = new JabInspector(new FakeBridge(systmOne()), "SystmOne");

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
        Node bare = new Node("frame", "SystmOne", null, 0, 0, 200, 200, 0);
        bare.add(new Node(null, null, null, 10, 10, 50, 50, 0));
        JabInspector plain = new JabInspector(new FakeBridge(bare), "SystmOne");

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

    // ---- modal dialogs ----

    /**
     * The window behind a modal stays in the tree, rendered and matchable, and its buttons are
     * routinely named the same. An unscoped locator hits both, which the engine treats as
     * ambiguous, so the suggestion has to name the window the control is in.
     */
    @Test
    void scopesTheLocatorToTheDialogWhenTheWindowBehindHasTheSameControl() {
        JabInspector withModal = new JabInspector(
                new FakeBridge(withCancelButton(systmOne()), confirmDialog()), "SystmOne");

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
                new FakeBridge(withCancelButton(systmOne()), confirmDialog()), "SystmOne");

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

    @Test
    void reportsUnavailableRatherThanFailingWhenTheBridgeIsAbsent() {
        JabInspector none = new JabInspector(new NullAccessBridge("no Access Bridge on this host"), "SystmOne");

        assertEquals(Optional.empty(), none.inspect(10, 10));
    }
}
