package com.newbrid.epr_automation.systmone.automation.jab;

import com.newbrid.epr_automation.systmone.common.dto.JabNode;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.model.NameMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Identifies the accessible element under a screen point and proposes a locator for it —
 * what turns "I clicked this control" into something a bot definition can be written from
 * (FR-JAB-02, FR-TOOL-03).
 *
 * <h4>Why it walks the tree rather than asking the bridge</h4>
 * The Access Bridge exposes {@code getAccessibleContextAt}, which would answer this in one
 * native call. It is deliberately not used. That entry point is not among the calls this
 * project has validated against a live SystmOne host, and adding an unproven native binding
 * to serve an authoring convenience would put the risk in the wrong place. Descending with
 * {@code info} and {@code child} — the two calls every locator resolution already depends on
 * — costs a few dozen more calls and inherits reliability that is already proven.
 *
 * <p>The walk descends into every container and lets the deepest node that covers the point win.
 * It deliberately does <em>not</em> prune on a container's own bounds or state: a {@code page tab}
 * reports the rectangle of its tab button, a viewport reports a clipped one, and some layout nodes
 * omit {@code showing} altogether — so a container's own attributes are evidence about the
 * container and not about what is inside it. Trusting them made the tool answer with the page tab
 * instead of the control that was clicked. Only a childless node is ruled out by its own bounds or
 * state, where the evidence really is about itself.</p>
 */
public class JabInspector {

    private static final Logger log = LoggerFactory.getLogger(JabInspector.class);

    /** Matches the JAB backend's traversal limits, so the tool cannot see what a run cannot. */
    private static final int MAX_DEPTH = 40;
    private static final int MAX_NODES = 8000;

    private final AccessBridge bridge;
    private final String windowTitleContains;

    public JabInspector(AccessBridge bridge, String windowTitleContains) {
        this.bridge = bridge;
        this.windowTitleContains = windowTitleContains == null ? "SystmOne" : windowTitleContains;
    }

    public boolean isAvailable() {
        return bridge.isAvailable();
    }

    /**
     * The element under {@code (x, y)}, the containers above it, a locator that resolves to
     * it, and how many elements that locator actually matches.
     *
     * @param x screen x in the worker's logical pixels
     * @param y screen y in the same space
     */
    public Optional<Inspection> inspect(int x, int y) {
        if (!bridge.isAvailable()) return Optional.empty();
        List<AcRef> roots = bridge.roots(windowTitleContains);
        try {
            // Every window is asked, and the most specific answer wins. Taking the first window to
            // merely contain the point would hand a modal's controls to the full-screen window
            // behind it, which contains every point on the desktop and answers with itself.
            Hit best = null;
            for (AcRef root : roots) {
                Hit hit = deepestAt(root, SiblingLookup.NONE, ScreenSpace.SCREEN, null, x, y, 0,
                        new int[]{0});
                if (hit != null && (best == null || area(leaf(hit)) < area(leaf(best)))) {
                    best = hit;
                }
            }
            if (best == null) {
                log.debug("No accessible element at ({},{}) in any window matching '{}'",
                        x, y, windowTitleContains);
                return Optional.empty();
            }

            return Optional.of(describe(roots, best));
        } finally {
            roots.forEach(bridge::release);
        }
    }

    /**
     * The element SystmOne considers focused, wherever it is — no coordinates involved.
     *
     * <p>This reads the {@code focused} state in the accessibility tree, not the operating
     * system's foreground window. That distinction is what makes it useful from a browser: the
     * author clicks a control in SystmOne, comes back to the tool, and the control they left is
     * still the focused one in SystmOne's own tree even though the browser now holds OS focus.</p>
     *
     * <p>A focused container and the focused control inside it both carry the state, so the
     * deepest one wins — that is the thing with the caret in it.</p>
     */
    public Optional<Inspection> focused() {
        if (!bridge.isAvailable()) return Optional.empty();
        List<AcRef> roots = bridge.roots(windowTitleContains);
        try {
            Hit best = null;
            int bestDepth = -1;
            for (AcRef root : roots) {
                Hit hit = deepestFocused(root, SiblingLookup.NONE, ScreenSpace.SCREEN, null, 0,
                        new int[]{0}, new ArrayList<>());
                if (hit == null) continue;
                int depth = hit.path().size();
                if (best == null || depth > bestDepth) {
                    best = hit;
                    bestDepth = depth;
                }
            }
            if (best == null) {
                log.debug("Nothing reports the focused state in any window matching '{}'",
                        windowTitleContains);
                return Optional.empty();
            }
            return Optional.of(describe(roots, best));
        } finally {
            roots.forEach(bridge::release);
        }
    }

    /** The element, its containers and a locator for it, from the path that reached it. */
    private Inspection describe(List<AcRef> roots, Hit hit) {
        List<AccessibleInfo> path = hit.path();
        AccessibleInfo element = path.get(path.size() - 1);
        List<JabNode> ancestors = path.subList(0, path.size() - 1).stream()
                .map(JabInspector::node)
                .toList();
        Suggestion suggestion = suggest(roots, element, windowScope(roots, path),
                hit.previous(), hit.following());
        return new Inspection(node(element, hit.bounds()), ancestors, node(hit.previous()),
                node(hit.following()), suggestion.locator(), suggestion.matchCount());
    }

    /**
     * The deepest node carrying the {@code focused} state, with the path to it. Unlike the
     * point lookup this cannot prune — focus is not a position, so every branch has to be asked.
     */
    private Hit deepestFocused(AcRef ref, SiblingLookup siblings, ScreenSpace inherited,
                               java.awt.Rectangle parentBounds, int depth, int[] visited,
                               List<AccessibleInfo> trail) {
        if (ref == null || depth > MAX_DEPTH || visited[0] > MAX_NODES) return null;
        visited[0]++;
        AccessibleInfo info = bridge.info(ref);
        if (info == null) return null;
        ScreenSpace space = inherited.forChild(info, parentBounds);

        // The focus walk has no position to prune on, so this is the only thing keeping it off
        // the parts of the tree that are built but not shown.
        if (info.hidesSubtree()) return null;

        trail.add(info);
        try {
            Hit best = null;
            if (info.hasState("focused") && info.width() > 0 && info.height() > 0) {
                best = new Hit(List.copyOf(trail), siblings.at(-1), siblings.at(1),
                        space.absolute(info));
            }
            for (int i = 0; i < info.childCount(); i++) {
                AcRef child = bridge.child(ref, i);
                Hit hit;
                try {
                    hit = deepestFocused(child, siblingsOf(ref, info, i), space,
                            space.absolute(info), depth + 1, visited, trail);
                } finally {
                    bridge.release(child);
                }
                // Deeper wins: a focused panel is context, the focused field inside it is the answer.
                if (hit != null && (best == null || hit.path().size() > best.path().size())) {
                    best = hit;
                }
            }
            return best;
        } finally {
            trail.remove(trail.size() - 1);
        }
    }

    /**
     * The element, its containers, its immediate neighbours, and the locator proposed for it.
     *
     * @param previousSibling the element immediately before it, or null — what an author needs to
     *                        see before they can write a {@code previousSibling} selector by hand
     * @param followingSibling the element immediately after it, or null
     */
    public record Inspection(JabNode element, List<JabNode> ancestors, JabNode previousSibling,
                             JabNode followingSibling, LocatorDefinition suggested, int matchCount) {}

    // ---------------------------------------------------------------- descent

    /**
     * @param previous the element immediately before the leaf, already decoded
     * @param following the element immediately after it
     *
     * <p>The neighbours are resolved as the hit is made rather than looked up afterwards. A lazy
     * lookup would outlive the handles it reads through: the walk gives every reference back as it
     * unwinds, so by the time a caller asked, the parent it would ask through is gone.</p>
     */
    private record Hit(List<AccessibleInfo> path, AccessibleInfo previous, AccessibleInfo following,
                       java.awt.Rectangle bounds) {}

    /**
     * The deepest node containing the point, with the path taken to reach it.
     *
     * <p><b>A container's own bounds never veto the descent.</b> They used to, as an obvious
     * optimisation, and it made the tool answer with the page tab instead of the control the
     * author clicked: a {@code page tab} reports the bounds of its tab <em>button</em> while its
     * content lives elsewhere, and a scroll pane or viewport reports a clipped rectangle. Any
     * container whose reported rectangle failed to cover its own children cut the walk off above
     * them. Bounds now decide only whether a node is itself an answer, never whether to look
     * inside it, so a container that misreports its extent costs a few more reads instead of the
     * right answer.</p>
     *
     * <p>Deepest wins, then smallest. Depth is what "the control rather than the thing it sits in"
     * means, and area only breaks ties between siblings at the same depth — a label drawn over its
     * own container, overlapping panes.</p>
     */
    private Hit deepestAt(AcRef ref, SiblingLookup siblings, ScreenSpace inherited,
                          java.awt.Rectangle parentBounds, int x, int y, int depth, int[] visited) {
        if (ref == null || depth > MAX_DEPTH || visited[0] > MAX_NODES) return null;
        visited[0]++;
        AccessibleInfo info = bridge.info(ref);
        if (info == null) return null;
        ScreenSpace space = inherited.forChild(info, parentBounds);

        // Two readings of where this node is: as corrected for the coordinate space inherited
        // from its ancestors, and exactly as reported. Either covering the point makes it a
        // candidate, because both mistakes this guards against — a cell measuring from its table,
        // a container misreporting its own extent — lose the deeper node, and accepting either
        // reading can only ever find more candidates, never fewer.
        java.awt.Rectangle corrected = space.absolute(info);
        java.awt.Rectangle asReported = info.bounds();
        java.awt.Rectangle bounds = corrected.contains(x, y) ? corrected : asReported;
        boolean coversPoint = (!corrected.isEmpty() && corrected.contains(x, y))
                || (!asReported.isEmpty() && asReported.contains(x, y));
        // A childless node that does not cover the point cannot be the answer and has nothing
        // inside it to ask, so it is the one case where geometry still ends the walk early.
        if (info.childCount() == 0 && !coversPoint) return null;
        // Same principle for the state set: a container's own attributes are evidence about the
        // container, not about what is inside it. A layout node that omits 'showing' — and some
        // do — must not hide the control the author actually clicked. A childless node's own state
        // is evidence about itself, so an unselected tab's controls are still ruled out there.
        if (info.childCount() == 0 && info.hidesSubtree()) return null;

        // A table that keeps its cells out of the tree cannot be walked into, so it is asked.
        // Without this a click in a Swing JTable can only ever answer with the table itself.
        if (info.childCount() == 0) {
            Hit inCell = cellAt(ref, info, space, corrected, x, y);
            if (inCell != null) return inCell;
        }

        Hit best = null;
        for (int i = 0; i < info.childCount(); i++) {
            AcRef child = bridge.child(ref, i);
            Hit hit;
            try {
                hit = deepestAt(child, siblingsOf(ref, info, i), space, corrected, x, y,
                        depth + 1, visited);
            } finally {
                // The inspector returns attributes, never handles, so every reference it opens
                // goes back on the way out.
                bridge.release(child);
            }
            if (hit != null && deeperOrTighter(hit, best)) {
                best = hit;
            }
        }

        List<AccessibleInfo> path = new ArrayList<>();
        path.add(info);
        if (best != null) {
            path.addAll(best.path());
            // The neighbours and the bounds that matter are the leaf's, not this node's.
            return new Hit(path, best.previous(), best.following(), best.bounds());
        }
        if (!coversPoint) {
            // Looked inside because a container's bounds are not to be trusted, and nothing inside
            // claimed the point: it is not an answer, just a node that could not rule itself out.
            return null;
        }
        return new Hit(path, siblings.at(-1), siblings.at(1), bounds);
    }

    /**
     * The cell of a table containing the point, found through the table API.
     *
     * <p>Rows are found by bisection rather than by scanning: a grid lays its rows out in order
     * down the screen, so the row containing a y can be found in about a dozen probes even in a
     * table of thousands. Scanning would mean a native call per row, which on a real grid is the
     * difference between a click that answers and one that appears to hang.</p>
     *
     * <p>If the bisection lands nowhere — a grid that does not lay rows out in order, or one whose
     * cells report no bounds — the table itself remains the answer, which is what it was before.</p>
     */
    private Hit cellAt(AcRef tableRef, AccessibleInfo tableInfo, ScreenSpace space,
                       java.awt.Rectangle tableBounds, int x, int y) {
        TableGeometry geometry = bridge.tableGeometry(tableRef);
        if (geometry == null || geometry.isEmpty()) return null;

        int row = rowAt(tableRef, geometry, space, tableBounds, y);
        if (row < 0) return null;
        for (int column = 0; column < geometry.columns(); column++) {
            AcRef cell = bridge.tableCell(tableRef, row, column);
            if (cell == null) continue;
            try {
                AccessibleInfo cellInfo = bridge.info(cell);
                if (cellInfo == null) continue;
                java.awt.Rectangle bounds = space.forChild(cellInfo, tableBounds).absolute(cellInfo);
                if (!bounds.contains(x, y)) continue;
                List<AccessibleInfo> path = new ArrayList<>();
                path.add(tableInfo);
                path.add(cellInfo);
                // The neighbours of a cell are the cells either side of it in its own row, which is
                // what an author writing a sibling selector against a grid would mean.
                return new Hit(path, cellBeside(tableRef, geometry, row, column - 1),
                        cellBeside(tableRef, geometry, row, column + 1), bounds);
            } finally {
                bridge.release(cell);
            }
        }
        return null;
    }

    /** The row whose cells span this y, by bisection over column 0, or -1. */
    private int rowAt(AcRef tableRef, TableGeometry geometry, ScreenSpace space,
                      java.awt.Rectangle tableBounds, int y) {
        int low = 0;
        int high = geometry.rows() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            java.awt.Rectangle bounds = cellBounds(tableRef, space, tableBounds, mid, 0);
            if (bounds == null) return -1;
            if (y < bounds.y) {
                high = mid - 1;
            } else if (y >= bounds.y + bounds.height) {
                low = mid + 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    private java.awt.Rectangle cellBounds(AcRef tableRef, ScreenSpace space,
                                          java.awt.Rectangle tableBounds, int row, int column) {
        AcRef cell = bridge.tableCell(tableRef, row, column);
        if (cell == null) return null;
        try {
            AccessibleInfo info = bridge.info(cell);
            return info == null ? null : space.forChild(info, tableBounds).absolute(info);
        } finally {
            bridge.release(cell);
        }
    }

    /** A neighbouring cell's attributes, or null at the ends of the row. */
    private AccessibleInfo cellBeside(AcRef tableRef, TableGeometry geometry, int row, int column) {
        if (column < 0 || column >= geometry.columns()) return null;
        AcRef cell = bridge.tableCell(tableRef, row, column);
        if (cell == null) return null;
        try {
            return bridge.info(cell);
        } finally {
            bridge.release(cell);
        }
    }

    /** Deeper beats shallower; at equal depth, the smaller element beats the larger. */
    private static boolean deeperOrTighter(Hit candidate, Hit best) {
        if (best == null) return true;
        int depth = candidate.path().size();
        int bestDepth = best.path().size();
        if (depth != bestDepth) return depth > bestDepth;
        return area(leaf(candidate)) < area(leaf(best));
    }

    private static AccessibleInfo leaf(Hit hit) {
        return hit.path().get(hit.path().size() - 1);
    }

    private static long area(AccessibleInfo info) {
        return (long) info.width() * info.height();
    }

    // ---------------------------------------------------------------- suggestion

    private record Suggestion(LocatorDefinition locator, int matchCount) {}

    /**
     * Propose the least specific locator that identifies this element uniquely.
     *
     * <p>Specificity is added only when it buys uniqueness. Role and name describe the control
     * in terms an author recognises and survive the window being moved or repopulated;
     * {@code index} is a position among siblings, which is exactly the thing a redesign or an
     * extra row breaks. So index is a last resort, and if even that leaves the locator matching
     * several elements, the most specific candidate is returned <em>with its real count</em>
     * rather than being dressed up as usable.</p>
     */
    private Suggestion suggest(List<AcRef> roots, AccessibleInfo element, String windowScope,
                               AccessibleInfo previous, AccessibleInfo following) {
        List<LocatorDefinition> ladder = candidates(element, windowScope,
                segmentFor(previous), segmentFor(following));
        Suggestion mostSpecific = null;
        for (LocatorDefinition candidate : ladder) {
            int count = countMatches(roots, candidate);
            mostSpecific = new Suggestion(candidate, count);
            if (count == 1) return mostSpecific;
        }
        return mostSpecific == null ? new Suggestion(null, 0) : mostSpecific;
    }

    /**
     * The window to scope a locator to, or null when scoping would only add brittleness.
     *
     * <p>Only worth adding when more than one window of the application is open, which is
     * exactly the modal-dialog case. The controls of a dialog are routinely named the same as
     * the ones in the window behind it - OK, Cancel, Search - and that window is still in the
     * tree, still rendered and still matched. Without a scope the locator hits both, and the
     * engine refuses to act on either.</p>
     *
     * <p>With a single window open this returns null: pinning a locator to the application's own
     * title would break the day that title gains a patient name or a release number, and buys
     * nothing while there is only one window to be in.</p>
     */
    private static String windowScope(List<AcRef> roots, List<AccessibleInfo> path) {
        if (roots.size() < 2 || path.isEmpty()) return null;
        AccessibleInfo window = path.get(0);
        String role = blankToNull(window.role());
        String name = blankToNull(window.name());
        if (role == null && name == null) return null;
        if (name == null) return role;
        return (role == null ? "" : role) + "[" + name + "]";
    }

    /**
     * A neighbour as a {@code role[name]} selector, or null when it has nothing to be named by.
     * A sibling that is itself anonymous distinguishes nothing.
     */
    private static String segmentFor(AccessibleInfo sibling) {
        if (sibling == null) return null;
        String role = blankToNull(sibling.role());
        String name = blankToNull(sibling.name());
        if (name != null) return (role == null ? "" : role) + "[" + name + "]";
        return role;
    }

    /** Candidate locators for one element, least specific first. */
    private static List<LocatorDefinition> candidates(AccessibleInfo element, String windowScope,
                                                      String previous, String following) {
        String role = blankToNull(element.role());
        String name = blankToNull(element.name());
        String description = blankToNull(element.description());
        if (role == null && name == null && description == null) return List.of();

        List<LocatorDefinition> ladder = new ArrayList<>();
        if (name != null) {
            rung(ladder, windowScope, role, name, null, null);
            if (description != null) rung(ladder, windowScope, role, name, description, null);
            siblingRungs(ladder, windowScope, role, name, description, previous, following);
            rung(ladder, windowScope, role, name, description, element.indexInParent());
        } else if (description != null) {
            rung(ladder, windowScope, role, null, description, null);
            siblingRungs(ladder, windowScope, role, null, description, previous, following);
            rung(ladder, windowScope, role, null, description, element.indexInParent());
        } else {
            // Role alone is rarely unique, but it is honest: the count says so, and the author
            // can see they need an offset locator or a nearby anchor instead.
            rung(ladder, windowScope, role, null, null, null);
            // The case sibling selectors exist for: a field with nothing of its own, identified by
            // the label beside it. Tried before index, which counts every sibling of every kind
            // and shifts the day the form gains one.
            siblingRungs(ladder, windowScope, role, null, null, previous, following);
            rung(ladder, windowScope, role, null, null, element.indexInParent());
        }
        return ladder;
    }

    /**
     * One rung of the ladder, then the same rung scoped to the window. Unscoped first: a locator
     * that already resolves uniquely should not carry a window title it does not need.
     */
    private static void rung(List<LocatorDefinition> ladder, String windowScope, String role,
                             String name, String description, Integer index) {
        NameMatch nameMatch = name == null ? null : NameMatch.EXACT;
        ladder.add(LocatorDefinition.jab(role, name, nameMatch, description, index, null));
        if (windowScope != null) {
            ladder.add(LocatorDefinition.jab(role, name, nameMatch, description, index, windowScope));
        }
    }

    /**
     * Rungs that identify the element by a neighbour: the one before, the one after, then both.
     * Preferred over {@code index} because a label beside a field describes the form as an author
     * reads it, while a position among siblings describes the layout as it happens to be today.
     */
    private static void siblingRungs(List<LocatorDefinition> ladder, String windowScope, String role,
                                     String name, String description, String previous, String following) {
        NameMatch nameMatch = name == null ? null : NameMatch.EXACT;
        if (previous != null) {
            ladder.add(LocatorDefinition.jab(role, name, nameMatch, description, null, null, previous, null));
            if (windowScope != null) {
                ladder.add(LocatorDefinition.jab(role, name, nameMatch, description, null, windowScope,
                        previous, null));
            }
        }
        if (following != null) {
            ladder.add(LocatorDefinition.jab(role, name, nameMatch, description, null, null, null, following));
        }
        if (previous != null && following != null) {
            ladder.add(LocatorDefinition.jab(role, name, nameMatch, description, null, windowScope,
                    previous, following));
        }
    }

    /**
     * How many elements this locator matches, by the engine's own rule, across every window the
     * engine would search — which now includes any modal dialog of the same application.
     */
    private int countMatches(List<AcRef> roots, LocatorDefinition locator) {
        int[] visited = {0};
        int total = 0;
        for (AcRef root : roots) {
            total += count(root, SiblingLookup.NONE, locator, 0, visited, new ArrayList<>());
        }
        return total;
    }

    /** Same lazy sibling reader the engine's search uses, and the same immediate release. */
    private SiblingLookup siblingsOf(AcRef parent, AccessibleInfo parentInfo, int index) {
        return offset -> {
            int wanted = index + offset;
            if (wanted < 0 || wanted >= parentInfo.childCount()) return null;
            AcRef sibling = bridge.child(parent, wanted);
            if (sibling == null) return null;
            try {
                return bridge.info(sibling);
            } finally {
                bridge.release(sibling);
            }
        };
    }

    private int count(AcRef ref, SiblingLookup siblings, LocatorDefinition locator, int depth,
                      int[] visited, List<AccessibleInfo> ancestors) {
        if (ref == null || depth > MAX_DEPTH || visited[0] > MAX_NODES) return 0;
        visited[0]++;
        AccessibleInfo info = bridge.info(ref);
        if (info == null) return 0;
        int found = JabMatching.matches(info, ancestors, siblings, locator) ? 1 : 0;
        // Pruned exactly as the engine prunes: a count that included hidden elements would report
        // an ambiguity a run would never meet, and vice versa.
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

    // ---------------------------------------------------------------- helpers

    private static JabNode node(AccessibleInfo i) {
        return i == null ? null : node(i, i.bounds());
    }

    /**
     * @param bounds where the element really is, which is not always where it says it is — a table
     *               cell often reports its rectangle relative to the table
     */
    private static JabNode node(AccessibleInfo i, java.awt.Rectangle bounds) {
        if (i == null) return null;
        return new JabNode(i.role(), i.name(), i.description(), i.states(),
                bounds.x, bounds.y, bounds.width, bounds.height, i.indexInParent(), i.childCount());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
