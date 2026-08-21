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
    void matchesUnnamedElements() {
        List<AccessibleInfo> deep = List.of(
                node("dialog", "Appointments"),
                node("panel", null),
                node("scroll pane", null),
                node("viewport", null),
                node("table", null));

        assertTrue(JabMatching.matches(BUTTON, deep, scopedTo("dialog > table")));
    }

    @Test
    void requiresTheSegmentsInOrder() {
        List<AccessibleInfo> deep = List.of(node("dialog", "Appointments"), node("table", "Slots"));

        assertFalse(JabMatching.matches(BUTTON, deep, scopedTo("table > dialog")),
                "outermost first — a reversed path describes a tree that does not exist");
    }

    // ---- sibling selectors ----

    /** A lookup over one parent's children, the way the tree walk supplies it. */
    private static SiblingLookup siblingsOf(List<AccessibleInfo> children, int index) {
        return offset -> {
            int wanted = index + offset;
            return wanted < 0 || wanted >= children.size() ? null : children.get(wanted);
        };
    }

    /** label, field, label, field — a form row, and the shape the selector exists for. */
    private static final List<AccessibleInfo> FORM = List.of(
            node("label", "Surname"),
            node("text", null),
            node("label", "Forename"),
            node("text", null));

    private static LocatorDefinition afterLabel(String previousSibling) {
        return LocatorDefinition.jab("text", null, null, null, null, null, previousSibling, null);
    }

    private static LocatorDefinition beforeLabel(String followingSibling) {
        return LocatorDefinition.jab("text", null, null, null, null, null, null, followingSibling);
    }

    @Test
    void identifiesAnUnlabelledFieldByTheLabelBeforeIt() {
        assertTrue(JabMatching.matches(FORM.get(1), IN_DIALOG, siblingsOf(FORM, 1),
                afterLabel("label[Surname]")));
        assertFalse(JabMatching.matches(FORM.get(3), IN_DIALOG, siblingsOf(FORM, 3),
                afterLabel("label[Surname]")),
                "the other field follows the Forename label, so the two are told apart");
    }

    @Test
    void identifiesAFieldByWhatFollowsIt() {
        assertTrue(JabMatching.matches(FORM.get(1), IN_DIALOG, siblingsOf(FORM, 1),
                beforeLabel("label[Forename]")));
    }

    /** Same role[name] grammar as ancestorPath: bare role, bare name, or both. */
    @Test
    void acceptsTheSameSegmentFormsAsAncestorPath() {
        assertTrue(JabMatching.matches(FORM.get(1), IN_DIALOG, siblingsOf(FORM, 1),
                afterLabel("label")), "role alone");
        assertTrue(JabMatching.matches(FORM.get(1), IN_DIALOG, siblingsOf(FORM, 1),
                afterLabel("[surname]")), "name alone, case-insensitively");
        assertFalse(JabMatching.matches(FORM.get(1), IN_DIALOG, siblingsOf(FORM, 1),
                afterLabel("push button[Surname]")), "role has to match too");
    }

    /**
     * Immediately adjacent, unlike ancestorPath. "The field after this label" is a claim about
     * adjacency; searching further could pin the locator to a label two fields up and click the
     * wrong control, which is worse than not resolving at all.
     */
    @Test
    void requiresTheSiblingToBeImmediatelyAdjacent() {
        List<AccessibleInfo> spaced = List.of(
                node("label", "Surname"),
                node("filler", null),
                node("text", null));

        assertFalse(JabMatching.matches(spaced.get(2), IN_DIALOG, siblingsOf(spaced, 2),
                afterLabel("label[Surname]")),
                "a strut between label and field defeats it, and does so visibly");
    }

    @Test
    void refusesWhenThereIsNoSiblingOnThatSide() {
        assertFalse(JabMatching.matches(FORM.get(0), IN_DIALOG, siblingsOf(FORM, 0),
                afterLabel("label[Surname]")), "nothing precedes the first child");
        assertFalse(JabMatching.matches(FORM.get(3), IN_DIALOG, siblingsOf(FORM, 3),
                beforeLabel("label")), "nothing follows the last child");
    }

    /** Same fail-safe as an unknown ancestor chain: refuse rather than assume. */
    @Test
    void refusesASiblingSelectorWhenSiblingsAreUnknown() {
        assertFalse(JabMatching.matches(FORM.get(1), IN_DIALOG, SiblingLookup.NONE,
                afterLabel("label[Surname]")));
        assertFalse(JabMatching.matches(FORM.get(1), IN_DIALOG, afterLabel("label[Surname]")),
                "the three-argument form knows no siblings, so it must refuse too");
    }

    @Test
    void bothSidesCanBeRequiredAtOnce() {
        LocatorDefinition between = LocatorDefinition.jab("text", null, null, null, null, null,
                "label[Surname]", "label[Forename]");

        assertTrue(JabMatching.matches(FORM.get(1), IN_DIALOG, siblingsOf(FORM, 1), between));
        assertFalse(JabMatching.matches(FORM.get(3), IN_DIALOG, siblingsOf(FORM, 3), between));
    }

    @Test
    void aBlankSelectorIsNoConstraintAtAll() {
        assertTrue(JabMatching.matches(FORM.get(1), IN_DIALOG, siblingsOf(FORM, 1),
                afterLabel("   ")), "blank normalises to absent rather than rejecting everything");
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
