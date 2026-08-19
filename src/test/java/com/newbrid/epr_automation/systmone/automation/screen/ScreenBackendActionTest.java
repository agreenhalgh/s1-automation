package com.newbrid.epr_automation.systmone.automation.screen;

import com.newbrid.epr_automation.systmone.common.model.ActionType;
import com.newbrid.epr_automation.systmone.sdk.locator.ActionParams;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Action-point geometry for the screen backend: a step's click offset shifts the point
 * away from the matched element's centre, and for DRAG it is the drag delta — applied
 * once, to the end point only, matching the JAB backend (FR-SR-06).
 */
class ScreenBackendActionTest {

    /** A 40x20 element at (100, 200) — centre (120, 210). */
    private static final ImageElementHandle ELEMENT =
            new ImageElementHandle(new Rectangle(100, 200, 40, 20), 0.99);

    /** Records the coordinates handed to the OS input layer instead of driving the mouse. */
    private static class RecordingInput extends RobotInput {
        final List<String> calls = new ArrayList<>();

        @Override public boolean isAvailable() { return true; }
        @Override public void click(int x, int y) { calls.add("click(" + x + "," + y + ")"); }
        @Override public void doubleClick(int x, int y) { calls.add("doubleClick(" + x + "," + y + ")"); }
        @Override public void rightClick(int x, int y) { calls.add("rightClick(" + x + "," + y + ")"); }
        @Override public void hover(int x, int y) { calls.add("hover(" + x + "," + y + ")"); }
        @Override public void drag(Point from, Point to) {
            calls.add("drag(" + from.x + "," + from.y + " -> " + to.x + "," + to.y + ")");
        }
    }

    private final RecordingInput input = new RecordingInput();
    private final ScreenBackend backend =
            new ScreenBackend(ref -> null, input, () -> null, 0.03);

    private static ActionParams offset(int x, int y) {
        return new ActionParams(null, null, x, y);
    }

    @Test
    void clicksTheCentreWhenNoOffsetIsGiven() {
        backend.perform(ActionType.CLICK, ELEMENT, ActionParams.NONE);

        assertEquals(List.of("click(120,210)"), input.calls);
    }

    @Test
    void shiftsTheClickPointByTheStepOffset() {
        backend.perform(ActionType.CLICK, ELEMENT, offset(40, -18));

        assertEquals(List.of("click(160,192)"), input.calls);
    }

    @Test
    void dragsFromTheCentreToTheCentrePlusTheOffsetOnce() {
        backend.perform(ActionType.DRAG, ELEMENT, offset(0, 60));

        // Not (120,270) -> (120,330): the offset is the delta, not also the start point.
        assertEquals(List.of("drag(120,210 -> 120,270)"), input.calls);
    }

    @Test
    void dragWithoutAnOffsetIsAZeroLengthDrag() {
        backend.perform(ActionType.DRAG, ELEMENT, ActionParams.NONE);

        assertEquals(List.of("drag(120,210 -> 120,210)"), input.calls);
    }
}
