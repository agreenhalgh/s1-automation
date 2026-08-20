package com.newbrid.epr_automation.systmone.automation.jab;

import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.model.NameMatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ancestorPath}, the field that lets a locator name the window its control is in — how a
 * step targets the OK button of a modal dialog rather than the identically named one in the
 * window behind it.
 */
class JabMatchingTest {

    private static AccessibleInfo node(String role, String name) {
        return new AccessibleInfo(role, name, null, "enabled,visible", 0, 0, 100, 30, 0, 0);
    }

    private static final AccessibleInfo BUTTON = node("push button", "Cancel");

    /** window > layout panel > button, the shape a real Swing dialog produces. */
    private static final List<AccessibleInfo> IN_DIALOG = List.of(
            node("dialog", "Confirm deletion of Mr Smith"),
            node("panel", null));

    private static final List<AccessibleInfo> IN_MAIN_WINDOW = List.of(
            node("frame", "SystmOne"),
            node("panel", null));

    private static LocatorDefinition scopedTo(String ancestorPath) {
        return LocatorDefinition.jab("push button", "Cancel", NameMatch.EXACT, null, null, ancestorPath);
    }

    @Test
    void matchesTheControlInsideTheNamedWindow() {
        assertTrue(JabMatching.matches(BUTTON, IN_DIALOG, scopedTo("dialog")));
    }

    @Test
    void rejectsTheSameControlInAnotherWindow() {
        assertFalse(JabMatching.matches(BUTTON, IN_MAIN_WINDOW, scopedTo("dialog")),
                "this is the whole point: the window behind a modal holds an identical button");
    }

    /**
     * Dialog titles carry what they are asking about, so the bracketed name is a contains —
     * a locator pinned to the whole title would work for exactly one patient.
     */
    @Test
    void matchesPartOfTheWindowTitle() {
        assertTrue(JabMatching.matches(BUTTON, IN_DIALOG, scopedTo("dialog[Confirm deletion]")));
        assertTrue(JabMatching.matches(BUTTON, IN_DIALOG, scopedTo("dialog[confirm DELETION]")),
                "case is not something an author should have to reproduce");
        assertFalse(JabMatching.matches(BUTTON, IN_DIALOG, scopedTo("dialog[Add a new patient]")));
    }

    @Test
    void matchesOnNameAloneWhenNoRoleIsGiven() {
        assertTrue(JabMatching.matches(BUTTON, IN_DIALOG, scopedTo("[Mr Smith]")));
    }

    /**
     * Segments are a subsequence, not a parent chain: Swing inserts viewports, scroll panes and
     * layout panels that no author should have to enumerate and that a release can add freely.
     */
    @Test
    void skipsInterveningContainers() {
        List<AccessibleInfo> deep = List.of(
                node("dialog", "Appointments"),
                node("panel", null),
                node("scroll pane", null),
                node("viewport", null),
                node("table", "Slots"));

        assertTrue(JabMatching.matches(BUTTON, deep, scopedTo("dialog > table[Slots]")));
    }

    @Test
    void requiresTheSegmentsInOrder() {
        List<AccessibleInfo> deep = List.of(node("dialog", "Appointments"), node("table", "Slots"));

        assertFalse(JabMatching.matches(BUTTON, deep, scopedTo("table > dialog")),
                "outermost first — a reversed path describes a tree that does not exist");
    }

    @Test
    void anUnscopedLocatorIsUnaffectedByAncestors() {
        LocatorDefinition plain = LocatorDefinition.jab("push button", "Cancel", NameMatch.EXACT);

        assertTrue(JabMatching.matches(BUTTON, IN_DIALOG, plain));
        assertTrue(JabMatching.matches(BUTTON, IN_MAIN_WINDOW, plain));
        assertTrue(JabMatching.matches(BUTTON, List.of(), plain));
    }

    /**
     * An element whose chain is not known cannot satisfy a scoped locator. Refusing is the
     * fail-safe direction: acting would mean guessing that it is in the right window.
     */
    @Test
    void refusesAScopedLocatorWhenTheChainIsUnknown() {
        assertFalse(JabMatching.matches(BUTTON, List.of(), scopedTo("dialog")));
    }
}
