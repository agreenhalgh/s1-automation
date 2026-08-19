package com.newbrid.epr_automation.systmone.automation.jab;

import com.newbrid.epr_automation.systmone.automation.screen.RobotInput;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.model.ActionType;
import com.newbrid.epr_automation.systmone.common.model.LocatorType;
import com.newbrid.epr_automation.systmone.common.model.NameMatch;
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
import java.util.regex.Pattern;

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
                search(root, locator, 0, new int[]{0}, matches);
                if (matches.size() > 1) break;
            }
            if (matches.size() == 1) {
                return Optional.of(matches.get(0));
            }
            if (matches.size() > 1) {
                log.debug("Ambiguous JAB locator {} matched {} elements — refusing to act",
                        locator.name(), matches.size());
                return Optional.empty(); // fail-safe
            }
            if (System.currentTimeMillis() >= deadline) break;
            sleep(Math.min(POLL_MS, Math.max(0, deadline - System.currentTimeMillis())));
        } while (System.currentTimeMillis() < deadline);
        return Optional.empty();
    }

    private void search(AcRef node, LocatorDefinition locator, int depth, int[] visited,
                        List<JabElementHandle> out) {
        if (node == null || depth > MAX_DEPTH || visited[0] > MAX_NODES || out.size() > 1) return;
        visited[0]++;
        AccessibleInfo info = bridge.info(node);
        if (info == null) return;

        if (matches(info, locator)) {
            out.add(new JabElementHandle(info.bounds(), node, describe(info)));
            if (out.size() > 1) return;
        }
        for (int i = 0; i < info.childCount(); i++) {
            AcRef child = bridge.child(node, i);
            search(child, locator, depth + 1, visited, out);
            if (out.size() > 1) return;
        }
    }

    private boolean matches(AccessibleInfo info, LocatorDefinition l) {
        if (l.role() != null && !l.role().equalsIgnoreCase(info.role())) return false;
        if (l.description() != null && !l.description().equalsIgnoreCase(info.description())) return false;
        if (l.index() != null && l.index() != info.indexInParent()) return false;
        if (l.name() != null && !nameMatches(l, info.name())) return false;
        // visible/rendered guard: an element with no bounds cannot be acted on
        return info.width() > 0 && info.height() > 0;
    }

    private boolean nameMatches(LocatorDefinition l, String actual) {
        if (actual == null) return false;
        NameMatch mode = l.nameMatch() == null ? NameMatch.EXACT : l.nameMatch();
        return switch (mode) {
            case EXACT -> actual.equals(l.name());
            case CONTAINS -> actual.contains(l.name());
            case REGEX -> Pattern.compile(l.name()).matcher(actual).find();
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

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
