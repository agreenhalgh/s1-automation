package com.newbrid.epr_automation.systmone.automation.screen;

import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@code KEYS} syntax bot authors type by hand. Parsing and key naming are tested
 * without a {@link java.awt.Robot}, so these run on a headless build agent.
 */
class RobotInputKeysTest {

    @Test
    void splitsASequenceIntoChordsInOrder() {
        assertEquals(List.of("DOWN", "DOWN", "ENTER"), RobotInput.chords("DOWN,DOWN,ENTER"));
    }

    /** A chord is still one chord: the separator must not disturb existing definitions. */
    @Test
    void leavesASingleChordAlone() {
        assertEquals(List.of("CTRL+S"), RobotInput.chords("CTRL+S"));
        assertEquals(List.of("ALT+F4"), RobotInput.chords("ALT+F4"));
    }

    @Test
    void toleratesSpacingAndStrayCommas() {
        assertEquals(List.of("DOWN", "ENTER"), RobotInput.chords(" DOWN , , ENTER ,"));
    }

    @Test
    void sendsNothingForNullOrBlank() {
        assertEquals(List.of(), RobotInput.chords(null));
        assertEquals(List.of(), RobotInput.chords("   "));
    }

    /** The comma is the separator, so the key itself has to be reachable by name. */
    @Test
    void namesTheCommaKey() {
        assertEquals(KeyEvent.VK_COMMA, RobotInput.keyCode("COMMA"));
    }

    @Test
    void mapsTheArrowKeys() {
        assertEquals(KeyEvent.VK_UP, RobotInput.keyCode("UP"));
        assertEquals(KeyEvent.VK_DOWN, RobotInput.keyCode("DOWN"));
    }

    /**
     * An unknown name fails the step rather than being skipped: a keystroke that silently
     * does not happen leaves the run acting on a screen it never actually reached.
     */
    @Test
    void rejectsAnUnknownKeyName() {
        assertThrows(IllegalArgumentException.class, () -> RobotInput.keyCode("PAGE_DOWN"));
    }
}
