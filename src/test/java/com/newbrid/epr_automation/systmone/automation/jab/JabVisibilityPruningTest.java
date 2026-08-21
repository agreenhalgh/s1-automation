package com.newbrid.epr_automation.systmone.automation.jab;

import com.newbrid.epr_automation.systmone.automation.jab.FakeTree.Node;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.model.NameMatch;
import com.newbrid.epr_automation.systmone.sdk.locator.ElementHandle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Traversal skips what is not on screen. Two things are at stake: the cost of decoding a subtree
 * nobody can act on, and the correctness of matching in it — a control on an unselected tab still
 * reports bounds, and clicking those coordinates hits whatever is drawn there instead.
 */
class JabVisibilityPruningTest {

    /**
     * A tab list where one tab is showing and two are not, each holding the same controls — the
     * shape that makes a role+name locator ambiguous and a traversal expensive.
     */
    private static Node tabbedForm(int fieldsPerTab) {
        Node dialog = FakeTree.node("dialog", "Patient Registration", null, 0, 0, 800, 600, 0);
        Node tabs = FakeTree.node("page tab list", null, null, 10, 40, 780, 540, 0);
        tabs.add(tab("Patient Details", true, fieldsPerTab, 0));
        tabs.add(tab("Relationships", false, fieldsPerTab, 1));
        tabs.add(tab("Consent", false, fieldsPerTab, 2));
        dialog.add(tabs);
        return dialog;
    }

    private static Node tab(String title, boolean showing, int fields, int index) {
        Node tab = FakeTree.node("page tab", title, null, 10, 60, 780, 500, index);
        if (!showing) tab.hidden();
        for (int i = 0; i < fields; i++) {
            tab.add(FakeTree.node("label", "Surname", null, 20, 80 + i * 30, 80, 20, i * 2));
            tab.add(FakeTree.node("text", null, null, 110, 80 + i * 30, 200, 24, i * 2 + 1));
        }
        return tab;
    }

    // ---- cost ----

    @Test
    void skipsTheContentsOfTabsThatAreNotShowing() {
        FakeTree.Counting bridge = FakeTree.counting(tabbedForm(20));
        JabBackend jab = new JabBackend(bridge, null, "SystmOne");

        jab.resolve(LocatorDefinition.jab("text", "nothing here", NameMatch.EXACT), Duration.ZERO);

        // dialog + tab list + 3 tabs + the 40 children of the one showing tab.
        assertEquals(45, bridge.reads(),
                "the two hidden tabs are decoded themselves but their 80 children are not");
    }

    /** The saving is the whole point: most of a SystmOne form is tabs nobody selected. */
    @Test
    void theSavingGrowsWithWhatIsHidden() {
        FakeTree.Counting pruned = FakeTree.counting(tabbedForm(50));
        new JabBackend(pruned, null, "SystmOne")
                .resolve(LocatorDefinition.jab("text", "nothing here", NameMatch.EXACT), Duration.ZERO);

        FakeTree.Counting everythingShowing = FakeTree.counting(allTabsShowing(50));
        new JabBackend(everythingShowing, null, "SystmOne")
                .resolve(LocatorDefinition.jab("text", "nothing here", NameMatch.EXACT), Duration.ZERO);

        assertTrue(pruned.reads() * 2 < everythingShowing.reads(),
                "pruning two of three tabs should more than halve the reads: "
                        + pruned.reads() + " against " + everythingShowing.reads());
    }

    private static Node allTabsShowing(int fieldsPerTab) {
        Node dialog = FakeTree.node("dialog", "Patient Registration", null, 0, 0, 800, 600, 0);
        Node tabs = FakeTree.node("page tab list", null, null, 10, 40, 780, 540, 0);
        for (int i = 0; i < 3; i++) tabs.add(tab("Tab " + i, true, fieldsPerTab, i));
        dialog.add(tabs);
        return dialog;
    }

    // ---- correctness ----

    /**
     * The reason this is not only an optimisation. Three tabs hold a field with the same
     * attributes; only one of them is on screen, and only that one is a legitimate target.
     */
    @Test
    void resolvesTheFieldOnTheShowingTabInsteadOfReportingAmbiguity() {
        JabBackend jab = new JabBackend(FakeTree.bridge(tabbedForm(1)), null, "SystmOne");
        LocatorDefinition field = LocatorDefinition.jab("text", null, null, null, null, null,
                "label[Surname]", null);

        Optional<ElementHandle> hit = jab.resolve(field, Duration.ZERO);

        assertTrue(hit.isPresent(), "the hidden tabs' identical fields no longer collide with it");
        assertEquals(80, hit.get().screenBounds().y);
    }

    @Test
    void findsNothingInsideAHiddenContainerEvenWhenItReportsBounds() {
        Node dialog = FakeTree.node("dialog", "Patient Registration", null, 0, 0, 800, 600, 0);
        Node hiddenTab = FakeTree.node("page tab", "Consent", null, 10, 60, 780, 500, 0).hidden();
        hiddenTab.add(FakeTree.node("push button", "Withdraw consent", null, 20, 80, 120, 24, 0));
        dialog.add(hiddenTab);
        JabBackend jab = new JabBackend(FakeTree.bridge(dialog), null, "SystmOne");

        assertEquals(Optional.empty(), jab.resolve(
                LocatorDefinition.jab("push button", "Withdraw consent", NameMatch.EXACT),
                Duration.ZERO), "a button on an unselected tab is not a button anyone can press");
    }

    /**
     * The safe-degradation rule. An application that reports no states at all must behave exactly
     * as it did before pruning existed — otherwise the first host whose bridge is quiet loses
     * every locator in the application at once.
     */
    @Test
    void traversesNormallyWhenTheBridgeReportsNoStates() {
        Node dialog = FakeTree.node("dialog", "Patient Registration", null, 0, 0, 800, 600, 0)
                .statesUnknown();
        Node panel = FakeTree.node("panel", null, null, 10, 40, 780, 500, 0).statesUnknown();
        panel.add(FakeTree.node("push button", "Save", null, 20, 60, 80, 24, 0).statesUnknown());
        dialog.add(panel);
        JabBackend jab = new JabBackend(FakeTree.bridge(dialog), null, "SystmOne");

        Optional<ElementHandle> hit = jab.resolve(
                LocatorDefinition.jab("push button", "Save", NameMatch.EXACT), Duration.ZERO);

        assertTrue(hit.isPresent(), "silence is not evidence of being hidden");
    }

    /** 'visible' alone is enough: not every toolkit reports 'showing' as well. */
    @Test
    void acceptsVisibleWithoutShowing() {
        AccessibleInfo visibleOnly = new AccessibleInfo("panel", null, null, "enabled,visible",
                0, 0, 100, 100, 0, 0);

        assertTrue(visibleOnly.isShowing());
        assertFalse(visibleOnly.hidesSubtree());
    }

    /** States are matched as tokens: a substring test would call 'multiselectable' selectable. */
    @Test
    void matchesStatesAsWholeTokens() {
        AccessibleInfo node = new AccessibleInfo("list", null, null,
                "enabled,multiselectable,showing", 0, 0, 10, 10, 0, 0);

        assertTrue(node.hasState("multiselectable"));
        assertFalse(node.hasState("selectable"), "not a substring match");
        assertTrue(node.hasState("SHOWING"), "case-insensitive");
    }
}
