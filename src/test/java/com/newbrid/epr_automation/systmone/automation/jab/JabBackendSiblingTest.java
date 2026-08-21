package com.newbrid.epr_automation.systmone.automation.jab;

import com.newbrid.epr_automation.systmone.automation.jab.FakeTree.Node;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.sdk.locator.ElementHandle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sibling selectors resolved through the engine's own traversal, not just through the predicate.
 *
 * <p>The predicate is tested in {@link JabMatchingTest}; what this covers is the plumbing that
 * feeds it — the index arithmetic and range guard in {@code JabBackend.siblingsOf}, where an
 * off-by-one would silently match the wrong neighbour and a missing guard would hand a negative
 * index to a native call.</p>
 */
class JabBackendSiblingTest {

    /** dialog > panel > [label Surname, text, label Forename, text] — one form row per pair. */
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

    private JabBackend backend(Node... windows) {
        return new JabBackend(FakeTree.bridge(windows), null, "SystmOne");
    }

    /** Two identical unlabelled fields; only the label beside each one tells them apart. */
    @Test
    void resolvesTheFieldAfterTheNamedLabel() {
        JabBackend jab = backend(patientForm());
        LocatorDefinition surnameField = LocatorDefinition.jab("text", null, null, null, null, null,
                "label[Surname]", null);

        Optional<ElementHandle> hit = jab.resolve(surnameField, Duration.ZERO);

        assertTrue(hit.isPresent(), "the label before it makes exactly one field match");
        assertEquals(60, hit.get().screenBounds().y, "the Surname row, not the Forename one");
    }

    @Test
    void resolvesTheOtherFieldFromItsOwnLabel() {
        JabBackend jab = backend(patientForm());
        LocatorDefinition forenameField = LocatorDefinition.jab("text", null, null, null, null, null,
                "label[Forename]", null);

        Optional<ElementHandle> hit = jab.resolve(forenameField, Duration.ZERO);

        assertTrue(hit.isPresent());
        assertEquals(100, hit.get().screenBounds().y);
    }

    @Test
    void resolvesByWhatFollowsAnElement() {
        JabBackend jab = backend(patientForm());
        LocatorDefinition beforeForenameLabel = LocatorDefinition.jab("text", null, null, null, null,
                null, null, "label[Forename]");

        Optional<ElementHandle> hit = jab.resolve(beforeForenameLabel, Duration.ZERO);

        assertTrue(hit.isPresent());
        assertEquals(60, hit.get().screenBounds().y, "the field between the two labels");
    }

    /** Without the selector the same locator is ambiguous, which is why the selector exists. */
    @Test
    void theSameLocatorWithoutASiblingSelectorIsAmbiguous() {
        JabBackend jab = backend(patientForm());

        Optional<ElementHandle> hit = jab.resolve(LocatorDefinition.jab("text", null, null),
                Duration.ZERO);

        assertEquals(Optional.empty(), hit, "two unlabelled fields match, so the engine refuses");
    }

    /**
     * The first and last children have nothing before and after them. The walk must range-check
     * before asking the bridge — the real one passes the index straight to a native call.
     */
    @Test
    void doesNotAskForASiblingBeyondTheEndsOfTheParent() {
        JabBackend jab = backend(patientForm());

        // The first label has no previous sibling; the last field has no following one. The fake
        // bridge throws on an out-of-range index, so a missing guard fails this test rather than
        // passing quietly.
        assertEquals(Optional.empty(), jab.resolve(
                LocatorDefinition.jab("label", "Surname", null, null, null, null, "label", null),
                Duration.ZERO));
        assertEquals(Optional.empty(), jab.resolve(
                LocatorDefinition.jab("text", null, null, null, 3, null, null, "label"),
                Duration.ZERO));
    }

    /** A window is nobody's sibling, and asking must not walk off the root either. */
    @Test
    void treatsATopLevelWindowAsHavingNoSiblings() {
        JabBackend jab = backend(patientForm());

        assertEquals(Optional.empty(), jab.resolve(
                LocatorDefinition.jab("dialog", null, null, null, null, null, "dialog", null),
                Duration.ZERO));
    }
}
