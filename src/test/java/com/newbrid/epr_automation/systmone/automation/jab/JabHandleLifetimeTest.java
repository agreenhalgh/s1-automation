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
 * Every {@code AccessibleContext} the bridge hands out is a reference the target JVM holds on our
 * behalf until it is told to let go. A traversal that walks a few hundred nodes per attempt, per
 * poll, per step therefore pins the whole application if it does not give them back — and Java's
 * garbage collector knows nothing about them, so nothing else will.
 *
 * <p>The tracking bridge fails on reading through a released handle and on a double release, so
 * those are checked by every test here simply by running.</p>
 */
class JabHandleLifetimeTest {

    private static Node form() {
        Node dialog = FakeTree.node("dialog", "Patient Registration", null, 0, 0, 800, 600, 0);
        Node panel = FakeTree.node("panel", null, null, 10, 40, 780, 500, 0);
        for (int i = 0; i < 8; i++) {
            panel.add(FakeTree.node("label", "Field " + i, null, 20, 60 + i * 30, 80, 20, i * 2));
            panel.add(FakeTree.node("text", null, null, 110, 60 + i * 30, 200, 24, i * 2 + 1));
        }
        dialog.add(panel);
        return dialog;
    }

    // ---- the engine ----

    @Test
    void aResolvedElementIsTheOnlyHandleLeftOpen() {
        FakeTree.Tracking bridge = FakeTree.tracking(form());
        JabBackend jab = new JabBackend(bridge, null, "SystmOne");

        Optional<ElementHandle> hit = jab.resolve(
                LocatorDefinition.jab("label", "Field 3", NameMatch.EXACT), Duration.ZERO);

        assertTrue(hit.isPresent());
        assertEquals(1, bridge.leaked().size(),
                "only the match is still held: " + bridge.leaked());
        assertTrue(bridge.opened() > 10, "the walk really did open handles: " + bridge.opened());
    }

    /** Closing the handle is what returns the last one, and the engine does it after acting. */
    @Test
    void closingTheHandleReturnsIt() {
        FakeTree.Tracking bridge = FakeTree.tracking(form());
        JabBackend jab = new JabBackend(bridge, null, "SystmOne");

        try (ElementHandle handle = jab.resolve(
                LocatorDefinition.jab("label", "Field 3", NameMatch.EXACT), Duration.ZERO)
                .orElseThrow()) {
            assertEquals(1, bridge.leaked().size());
        }

        assertEquals(0, bridge.leaked().size(), "nothing is held once the step has acted");
        assertEquals(bridge.opened(), bridge.released(), "every reference accounted for");
    }

    /** A locator that matches nothing must leave nothing behind: the common case in a retry loop. */
    @Test
    void aFailedResolutionHoldsNothing() {
        FakeTree.Tracking bridge = FakeTree.tracking(form());
        JabBackend jab = new JabBackend(bridge, null, "SystmOne");

        Optional<ElementHandle> hit = jab.resolve(
                LocatorDefinition.jab("push button", "Nowhere", NameMatch.EXACT), Duration.ZERO);

        assertEquals(Optional.empty(), hit);
        assertEquals(0, bridge.leaked().size(), "leaked: " + bridge.leaked());
        assertEquals(bridge.opened(), bridge.released());
    }

    /** Ambiguity returns nothing, so both candidates have to go back too. */
    @Test
    void anAmbiguousResolutionHoldsNothing() {
        FakeTree.Tracking bridge = FakeTree.tracking(form());
        JabBackend jab = new JabBackend(bridge, null, "SystmOne");

        Optional<ElementHandle> hit = jab.resolve(LocatorDefinition.jab("text", null, null),
                Duration.ZERO);

        assertEquals(Optional.empty(), hit, "eight identical fields");
        assertEquals(0, bridge.leaked().size(), "leaked: " + bridge.leaked());
    }

    /** The window handles are references too, and a match may be a window itself. */
    @Test
    void windowHandlesAreReturnedIncludingWhenTheMatchIsAWindow() {
        FakeTree.Tracking bridge = FakeTree.tracking(form());
        JabBackend jab = new JabBackend(bridge, null, "SystmOne");

        try (ElementHandle handle = jab.resolve(
                LocatorDefinition.jab("dialog", "Patient Registration", NameMatch.EXACT),
                Duration.ZERO).orElseThrow()) {
            assertEquals(1, bridge.leaked().size(), "the window is the match, so it stays open");
        }

        assertEquals(0, bridge.leaked().size());
    }

    /** Sibling selectors read a neighbour's attributes; the neighbour is not kept. */
    @Test
    void aSiblingReadDoesNotOutliveTheRead() {
        FakeTree.Tracking bridge = FakeTree.tracking(form());
        JabBackend jab = new JabBackend(bridge, null, "SystmOne");

        try (ElementHandle handle = jab.resolve(LocatorDefinition.jab("text", null, null, null, null,
                null, "label[Field 3]", null), Duration.ZERO).orElseThrow()) {
            assertEquals(1, bridge.leaked().size(), "leaked: " + bridge.leaked());
        }

        assertEquals(0, bridge.leaked().size());
    }

    // ---- the tool ----

    /** The inspector returns attributes, never handles, so it should finish holding nothing. */
    @Test
    void inspectingAPointHoldsNothingAfterwards() {
        FakeTree.Tracking bridge = FakeTree.tracking(form());

        new JabInspector(bridge, "SystmOne").inspect(150, 160).orElseThrow();

        assertEquals(0, bridge.leaked().size(), "leaked: " + bridge.leaked());
        assertEquals(bridge.opened(), bridge.released(), "every reference accounted for");
    }

    @Test
    void readingTheFocusedElementHoldsNothingAfterwards() {
        Node dialog = form();
        dialog.add(FakeTree.node("push button", "Save", null, 20, 500, 80, 24, 1).focused());
        FakeTree.Tracking bridge = FakeTree.tracking(dialog);

        new JabInspector(bridge, "SystmOne").focused().orElseThrow();

        assertEquals(0, bridge.leaked().size(), "leaked: " + bridge.leaked());
    }

    /**
     * The suggestion ladder walks the tree once per candidate rung, so it opens the most handles
     * of anything here — and holds none of them.
     */
    @Test
    void suggestingALocatorHoldsNothingAfterwards() {
        FakeTree.Tracking bridge = FakeTree.tracking(form());

        new JabInspector(bridge, "SystmOne").inspect(150, 160).orElseThrow();

        assertTrue(bridge.opened() > 20, "several traversals: " + bridge.opened());
        assertEquals(0, bridge.leaked().size(), "leaked: " + bridge.leaked());
    }
}
