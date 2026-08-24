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
public class JabBackend implements AutomationBackend, com.newbrid.epr_automation.systmone.sdk.locator.TableAware {

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
            List<AcRef> roots = bridge.roots(windowTitleContains);
            try {
                for (AcRef root : roots) {
                    search(root, SiblingLookup.NONE, ScreenSpace.SCREEN, null, locator, 0,
                            new int[]{0}, matches, new ArrayList<>());
                    if (matches.size() > 1) break;
                }
            } finally {
                // Window handles are ours to give back, unless the match is a window itself.
                for (AcRef root : roots) {
                    if (!retains(matches, root)) bridge.release(root);
                }
            }
            if (matches.size() == 1) {
                // Ownership passes to the caller, which closes it once the step has acted.
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
                // Nothing is returned, so nothing transfers: both candidates go back.
                matches.forEach(JabElementHandle::close);
                return Optional.empty(); // fail-safe
            }
            if (System.currentTimeMillis() >= deadline) break;
            sleep(Math.min(POLL_MS, Math.max(0, deadline - System.currentTimeMillis())));
        } while (System.currentTimeMillis() < deadline);
        return Optional.empty();
    }

    /**
     * Resolve one cell through the table API, for a grid that keeps its cells out of the tree.
     *
     * <p>Addressed rather than searched: the row and column are known, so this is one lookup
     * instead of a walk, and it reaches cells no walk could find. The cell's own attributes are
     * then held to the rest of the locator exactly as a walked node would be — a locator naming a
     * role the cell does not have still does not match.</p>
     */
    private void searchTableCell(AcRef node, AccessibleInfo tableInfo, ScreenSpace space,
                                 java.awt.Rectangle tableBounds, LocatorDefinition locator,
                                 List<JabElementHandle> out, List<AccessibleInfo> ancestors) {
        TableGeometry geometry = bridge.tableGeometry(node);
        if (geometry == null || geometry.isEmpty()) return;
        Integer wantedRow = locator.rowValue();
        if (wantedRow == null) return;   // an unresolved ${var} addresses no cell
        int row = wantedRow;
        int column = locator.columnValue() == null ? 0 : locator.columnValue();
        if (row >= geometry.rows() || column >= geometry.columns()) {
            log.debug("Table is {}x{}, so there is no cell at row {} column {}",
                    geometry.rows(), geometry.columns(), row, column);
            return;
        }

        AcRef cell = bridge.tableCell(node, row, column);
        if (cell == null) return;
        boolean keep = false;
        try {
            AccessibleInfo cellInfo = bridge.info(cell);
            if (cellInfo == null) return;
            // The cell's ancestors are the table's plus the table: it is a child of the table in
            // every sense except being in the tree, so ancestorPath and parent still mean what
            // they say.
            List<AccessibleInfo> cellAncestors = new ArrayList<>(ancestors);
            cellAncestors.add(tableInfo);
            // Row and column are known to be right, and are not re-derived from a chain that has
            // no row node in it.
            LocatorDefinition withoutPosition = locator.withCellText(null, null);
            if (!JabMatching.matches(cellInfo, cellAncestors, SiblingLookup.NONE, withoutPosition)) {
                return;
            }
            java.awt.Rectangle cellBounds = space.forChild(cellInfo, tableBounds).absolute(cellInfo);
            out.add(new JabElementHandle(cellBounds, cell, describe(cellInfo), bridge));
            keep = true;
            log.debug("Resolved row {} column {} of a {}x{} table through the table API",
                    row, column, geometry.rows(), geometry.columns());
        } finally {
            if (!keep) bridge.release(cell);
        }
    }

    /**
     * How many elements match, across every window the engine searches. Same predicate, same
     * visibility pruning and same handle discipline as {@link #resolve}, so a count describes the
     * elements a run could actually act on — one at a time.
     */
    @Override
    public int count(LocatorDefinition locator, Duration timeout) {
        List<AcRef> roots = bridge.roots(windowTitleContains);
        try {
            int[] visited = {0};
            int total = 0;
            for (AcRef root : roots) {
                total += count(root, SiblingLookup.NONE, locator, 0, visited, new ArrayList<>());
            }
            return total;
        } finally {
            roots.forEach(bridge::release);
        }
    }

    /**
     * How many rows or columns a table has, as the application counts them.
     *
     * <p>Different from counting matches in the tree, and better where the two disagree: a table
     * that exposes no cell children reports zero rows by traversal however many it has, and a
     * virtualised grid reports only the rows it is showing. This asks the model.</p>
     *
     * @return null when the locator does not resolve to a table, or the application will not say —
     *         the caller then falls back to counting what the tree exposes
     */
    @Override
    public TableGeometry tableGeometry(LocatorDefinition locator, Duration timeout) {
        Optional<ElementHandle> table = resolve(locator, timeout);
        if (table.isEmpty()) return null;
        try (ElementHandle handle = table.get()) {
            return handle instanceof JabElementHandle jab ? bridge.tableGeometry(jab.ref()) : null;
        }
    }

    private int count(AcRef ref, SiblingLookup siblings, LocatorDefinition locator, int depth,
                      int[] visited, List<AccessibleInfo> ancestors) {
        if (ref == null || depth > MAX_DEPTH || visited[0] > MAX_NODES) return 0;
        visited[0]++;
        AccessibleInfo info = bridge.info(ref);
        if (info == null) return 0;
        int found = JabMatching.matches(info, ancestors, siblings, locator) ? 1 : 0;
        if (info.hidesSubtree()) return found;
        ancestors.add(info);
        try {
            for (int i = 0; i < info.childCount(); i++) {
                AcRef child = bridge.child(ref, i);
                try {
                    found += count(child, siblingsOf(ref, info, i), locator, depth + 1, visited,
                            ancestors);
                } finally {
                    bridge.release(child);
                }
            }
        } finally {
            ancestors.remove(ancestors.size() - 1);
        }
        return found;
    }

    /**
     * @param ancestors the containers above {@code node}, outermost first, maintained as the
     *                  walk descends so a locator can be scoped by {@code ancestorPath} — which
     *                  is how a step targets a control inside a modal dialog rather than the
     *                  identically named one in the window behind it
     */
    private void search(AcRef node, SiblingLookup siblings, ScreenSpace inherited,
                        java.awt.Rectangle parentBounds, LocatorDefinition locator, int depth,
                        int[] visited, List<JabElementHandle> out, List<AccessibleInfo> ancestors) {
        if (node == null || depth > MAX_DEPTH || visited[0] > MAX_NODES || out.size() > 1) return;
        visited[0]++;
        AccessibleInfo info = bridge.info(node);
        if (info == null) return;
        // Read once, here: deciding the coordinate space from inside the node keeps the walk to
        // one decode per node, where reading each child from its parent's loop doubled it.
        ScreenSpace space = inherited.forChild(info, parentBounds);

        // Corrected bounds, because these are the coordinates the mouse is driven to. A table cell
        // that reports its rectangle relative to the table would otherwise be clicked near the top
        // of the desktop.
        java.awt.Rectangle bounds = space.absolute(info);
        if (JabMatching.matches(info, ancestors, siblings, locator)) {
            out.add(new JabElementHandle(bounds, node, describe(info), bridge));
            if (out.size() > 1) return;
        }
        // Nothing inside a container that is not on screen can be acted on, so the whole subtree
        // is skipped rather than decoded. On a SystmOne form that is most of the tree: the tabs
        // nobody selected, the cards nobody flipped to, the dialogs built but not shown. It also
        // stops those consuming the MAX_NODES budget before the walk reaches the visible control
        // the step was actually looking for.
        if (info.hidesSubtree()) return;
        // A table's cells are often not in the tree at all — a Swing JTable exposes none — so
        // when the locator asks for one by position, the table itself is asked. Only then: the call
        // costs nothing on a grid whose cells are already walkable, because it is not made.
        if (locator.row() != null && info.childCount() == 0) {
            searchTableCell(node, info, space, bounds, locator, out, ancestors);
            if (!out.isEmpty()) return;
        }
        ancestors.add(info);
        try {
            for (int i = 0; i < info.childCount(); i++) {
                AcRef child = bridge.child(node, i);
                try {
                    search(child, siblingsOf(node, info, i), space, bounds, locator, depth + 1,
                            visited, out, ancestors);
                } finally {
                    // Released unless this exact handle is the match being returned. A handle for a
                    // node deeper in the subtree is a separate reference, so releasing this one
                    // does not invalidate it.
                    if (!retains(out, child)) bridge.release(child);
                }
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
            if (sibling == null) return null;
            try {
                // Only the decoded attributes are wanted, so the reference goes straight back.
                return bridge.info(sibling);
            } finally {
                bridge.release(sibling);
            }
        };
    }

    /** Whether one of these matches is this exact handle, and so owns it. */
    private static boolean retains(List<JabElementHandle> matches, AcRef ref) {
        return matches.stream().anyMatch(m -> m.ref().equals(ref));
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
