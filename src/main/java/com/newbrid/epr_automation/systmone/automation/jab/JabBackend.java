package com.newbrid.epr_automation.systmone.automation.jab;

import com.newbrid.epr_automation.systmone.automation.screen.RobotInput;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.model.ActionType;
import com.newbrid.epr_automation.systmone.common.model.LocatorType;
import com.newbrid.epr_automation.systmone.sdk.locator.ActionParams;
import com.newbrid.epr_automation.systmone.sdk.locator.AutomationBackend;
import com.newbrid.epr_automation.systmone.sdk.locator.ElementHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Java Access Bridge automation backend (LocatorType.JAB) — the preferred, attribute-
 * based strategy (§2.2, §3.1). Traverses the accessibility tree to resolve locators and
 * drives elements via accessible actions, falling back to coordinate clicks derived from
 * accessible bounds where an action API is unavailable (FR-JAB-08).
 *
 * <p>Fail-safe (NFR-REL-04): if a locator matches more than one element in a single
 * traversal it is treated as ambiguous and resolves to empty — the engine never guesses.</p>
 */
public class JabBackend implements AutomationBackend {

    private static final Logger log = LoggerFactory.getLogger(JabBackend.class);
    private static final int MAX_DEPTH = 40;
    private static final int MAX_NODES = 8000;
    private static final long POLL_MS = 150;

    private final AccessBridge bridge;
    private final RobotInput robot;
    private final String windowTitleContains;

    public JabBackend(AccessBridge bridge, RobotInput robot, String windowTitleContains) {
        this.bridge = bridge;
        this.robot = robot;
        this.windowTitleContains = windowTitleContains == null ? "SystmOne" : windowTitleContains;
    }

    @Override
    public LocatorType handles() {
        return LocatorType.JAB;
    }

    @Override
    public boolean isAvailable() {
        return bridge.isAvailable();
    }

    @Override
    public Optional<ElementHandle> resolve(LocatorDefinition locator, Duration timeout) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeout.toMillis());
        do {
            List<JabElementHandle> matches = new ArrayList<>();
            for (AcRef root : bridge.roots(windowTitleContains)) {
                search(root, SiblingLookup.NONE, locator, 0, new int[]{0}, matches, new ArrayList<>());
                if (matches.size() > 1) break;
            }
            if (matches.size() == 1) {
                return Optional.of(matches.get(0));
            }
            if (matches.size() > 1) {
                // Warn, not debug: this resolves to empty exactly like "nothing found", so without
                // a message the two are indistinguishable from the outside — and the fix for each
                // is the opposite of the fix for the other.
                //
                // "more than one" rather than a count: the search stops as soon as a second match
                // appears, so it does not know whether there were two or twenty. Reporting
                // matches.size() here claimed a number it had not counted.
                log.warn("JAB locator {} matched more than one element, so the step will not act on "
                                + "any of them (NFR-REL-04). The first two were {} and {}. Add "
                                + "something that tells them apart — a name, a description, or an "
                                + "ancestorPath naming the window or tab the intended one is in.",
                        locator.describe(), describe(matches.get(0)), describe(matches.get(1)));
                return Optional.empty(); // fail-safe
            }
            if (System.currentTimeMillis() >= deadline) break;
            sleep(Math.min(POLL_MS, Math.max(0, deadline - System.currentTimeMillis())));
        } while (System.currentTimeMillis() < deadline);
        return Optional.empty();
    }

    /**
     * @param ancestors the containers above {@code node}, outermost first, maintained as the
     *                  walk descends so a locator can be scoped by {@code ancestorPath} — which
     *                  is how a step targets a control inside a modal dialog rather than the
     *                  identically named one in the window behind it
     */
    private void search(AcRef node, SiblingLookup siblings, LocatorDefinition locator, int depth,
                        int[] visited, List<JabElementHandle> out, List<AccessibleInfo> ancestors) {
        if (node == null || depth > MAX_DEPTH || visited[0] > MAX_NODES || out.size() > 1) return;
        visited[0]++;
        AccessibleInfo info = bridge.info(node);
        if (info == null) return;

        if (JabMatching.matches(info, ancestors, siblings, locator)) {
            out.add(new JabElementHandle(info.bounds(), node, describe(info)));
            if (out.size() > 1) return;
        }
        // Nothing inside a container that is not on screen can be acted on, so the whole subtree
        // is skipped rather than decoded. On a SystmOne form that is most of the tree: the tabs
        // nobody selected, the cards nobody flipped to, the dialogs built but not shown. It also
        // stops those consuming the MAX_NODES budget before the walk reaches the visible control
        // the step was actually looking for.
        if (info.hidesSubtree()) return;
        ancestors.add(info);
        try {
            for (int i = 0; i < info.childCount(); i++) {
                AcRef child = bridge.child(node, i);
                search(child, siblingsOf(node, info, i), locator, depth + 1, visited, out, ancestors);
                if (out.size() > 1) return;
            }
        } finally {
            ancestors.remove(ancestors.size() - 1);
        }
    }

    /**
     * Reads the child either side of position {@code index} under {@code parent}, on demand — a
     * locator that names no sibling never pays for one.
     */
    private SiblingLookup siblingsOf(AcRef parent, AccessibleInfo parentInfo, int index) {
        return offset -> {
            int wanted = index + offset;
            // The native call takes an index on trust, so the range check happens here.
            if (wanted < 0 || wanted >= parentInfo.childCount()) return null;
            AcRef sibling = bridge.child(parent, wanted);
            return sibling == null ? null : bridge.info(sibling);
        };
    }

    @Override
    public String perform(ActionType action, ElementHandle element, ActionParams params) {
        JabElementHandle h = (JabElementHandle) element;
        AcRef ref = h.ref();
        AccessibleInfo info = bridge.info(ref);
        Rectangle b = h.screenBounds();
        int cx = b.x + b.width / 2;
        int cy = b.y + b.height / 2;

        switch (action) {
            case CLICK, SELECT -> { if (!bridge.doAction(ref, "click")) robotClick(cx, cy); }
            case FOCUS -> { if (!bridge.doAction(ref, "focus")) robotClick(cx, cy); }
            case EXPAND -> bridge.doAction(ref, "toggle expand");
            case COLLAPSE -> bridge.doAction(ref, "toggle expand");
            case DOUBLE_CLICK -> requireRobot().doubleClick(cx, cy);
            case RIGHT_CLICK -> requireRobot().rightClick(cx, cy);
            case HOVER -> requireRobot().hover(cx, cy);
            case DRAG -> requireRobot().drag(new java.awt.Point(cx, cy),
                    new java.awt.Point(cx + params.offsetXOr(0), cy + params.offsetYOr(0)));
            case SET_TEXT -> {
                String text = params.text() == null ? "" : params.text();
                if (!bridge.setText(ref, text)) {
                    RobotInput r = requireRobot();
                    r.click(cx, cy);
                    r.keys("CTRL+A");
                    r.type(text);
                }
            }
            case KEYS -> requireRobot().keys(params.keys() != null ? params.keys() : params.text());
            case GET_TEXT -> {
                String t = bridge.getText(ref);
                return t != null ? t : (info != null ? info.name() : null);
            }
            case READ_STATE -> {
                return info != null ? info.states() : null;
            }
            case WAIT -> { /* resolution satisfies WAIT */ }
            case ACTIVATE -> throw new UnsupportedOperationException(
                    "ACTIVATE acts on a window, not an element — the engine performs it "
                            + "through WindowControl, so no backend implements it");
        }
        return null;
    }

    private void robotClick(int x, int y) {
        requireRobot().click(x, y);
    }

    private RobotInput requireRobot() {
        if (robot == null || !robot.isAvailable()) {
            throw new UnsupportedOperationException("No Robot available for coordinate-based action");
        }
        return robot;
    }

    @Override
    public BufferedImage captureScreenshot() {
        return robot != null && robot.isAvailable() ? robot.captureScreen() : null;
    }

    private static String describe(AccessibleInfo i) {
        return "JAB[" + i.role() + ":" + i.name() + "]";
    }

    /** A matched element with where it is, so two collisions can be told apart in a log. */
    private static String describe(JabElementHandle handle) {
        Rectangle b = handle.screenBounds();
        return handle.description() + " at " + b.x + "," + b.y + " " + b.width + "x" + b.height;
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
