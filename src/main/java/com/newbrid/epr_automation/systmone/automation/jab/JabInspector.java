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
 * <p>The descent is cheap because it prunes: a child is only entered if the point falls
 * inside its parent. A container that reports no bounds is entered anyway, since a wrong
 * zero from a layout node would otherwise hide everything beneath it.</p>
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

        // Every window is asked, and the most specific answer wins. Taking the first window to
        // merely contain the point would hand a modal's controls to the full-screen window
        // behind it, which contains every point on the desktop and answers with itself.
        Hit best = null;
        for (AcRef root : roots) {
            Hit hit = deepestAt(root, SiblingLookup.NONE, x, y, 0, new int[]{0});
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
        Hit best = null;
        int bestDepth = -1;
        for (AcRef root : roots) {
            Hit hit = deepestFocused(root, SiblingLookup.NONE, 0, new int[]{0}, new ArrayList<>());
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
    }

    /** The element, its containers and a locator for it, from the path that reached it. */
    private Inspection describe(List<AcRef> roots, Hit hit) {
        List<AccessibleInfo> path = hit.path();
        AccessibleInfo element = path.get(path.size() - 1);
        List<JabNode> ancestors = path.subList(0, path.size() - 1).stream()
                .map(JabInspector::node)
                .toList();
        // Resolved once, here: the neighbours of the element that won, and nothing else.
        AccessibleInfo previous = hit.siblings().at(-1);
        AccessibleInfo following = hit.siblings().at(1);
        Suggestion suggestion = suggest(roots, element, windowScope(roots, path), previous, following);
        return new Inspection(node(element), ancestors, node(previous), node(following),
                suggestion.locator(), suggestion.matchCount());
    }

    /**
     * The deepest node carrying the {@code focused} state, with the path to it. Unlike the
     * point lookup this cannot prune — focus is not a position, so every branch has to be asked.
     */
    private Hit deepestFocused(AcRef ref, SiblingLookup siblings, int depth, int[] visited,
                               List<AccessibleInfo> trail) {
        if (ref == null || depth > MAX_DEPTH || visited[0] > MAX_NODES) return null;
        visited[0]++;
        AccessibleInfo info = bridge.info(ref);
        if (info == null) return null;

        trail.add(info);
        try {
            Hit best = null;
            if (info.hasState("focused") && info.width() > 0 && info.height() > 0) {
                best = new Hit(List.copyOf(trail), siblings);
            }
            for (int i = 0; i < info.childCount(); i++) {
                Hit child = deepestFocused(bridge.child(ref, i), siblingsOf(ref, info, i),
                        depth + 1, visited, trail);
                // Deeper wins: a focused panel is context, the focused field inside it is the answer.
                if (child != null && (best == null || child.path().size() > best.path().size())) {
                    best = child;
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
     * @param siblings reads the elements either side of the leaf. Carried rather than resolved
     *                 because only the winning hit's neighbours are ever wanted, and each costs
     *                 native calls
     */
    private record Hit(List<AccessibleInfo> path, SiblingLookup siblings) {}

    /**
     * The deepest node containing the point, with the path taken to reach it. Where several
     * children contain the point — overlapping panes, a label drawn over its container — the
     * smallest wins, because that is the one the author was pointing at.
     */
    private Hit deepestAt(AcRef ref, SiblingLookup siblings, int x, int y, int depth, int[] visited) {
        if (ref == null || depth > MAX_DEPTH || visited[0] > MAX_NODES) return null;
        visited[0]++;
        AccessibleInfo info = bridge.info(ref);
        if (info == null) return null;

        boolean sized = info.width() > 0 && info.height() > 0;
        if (sized && !info.bounds().contains(x, y)) return null;

        Hit best = null;
        for (int i = 0; i < info.childCount(); i++) {
            Hit child = deepestAt(bridge.child(ref, i), siblingsOf(ref, info, i), x, y,
                    depth + 1, visited);
            if (child != null && (best == null || area(leaf(child)) < area(leaf(best)))) {
                best = child;
            }
        }

        List<AccessibleInfo> path = new ArrayList<>();
        path.add(info);
        if (best != null) {
            path.addAll(best.path());
            // The neighbours that matter are the leaf's, not this node's.
            return new Hit(path, best.siblings());
        }
        if (!sized) {
            // Entered only because its own bounds were unusable, and nothing inside claimed the
            // point: it is not an answer, just a container that could not rule itself out.
            return null;
        }
        return new Hit(path, siblings);
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

    private int count(AcRef ref, SiblingLookup siblings, LocatorDefinition locator, int depth,
                      int[] visited, List<AccessibleInfo> ancestors) {
        if (ref == null || depth > MAX_DEPTH || visited[0] > MAX_NODES) return 0;
        visited[0]++;
        AccessibleInfo info = bridge.info(ref);
        if (info == null) return 0;
        int found = JabMatching.matches(info, ancestors, siblings, locator) ? 1 : 0;
        ancestors.add(info);
        try {
            for (int i = 0; i < info.childCount(); i++) {
                found += count(bridge.child(ref, i), siblingsOf(ref, info, i), locator,
                        depth + 1, visited, ancestors);
            }
        } finally {
            ancestors.remove(ancestors.size() - 1);
        }
        return found;
    }

    /** Same lazy sibling reader the engine's search uses, so a count means what a run will do. */
    private SiblingLookup siblingsOf(AcRef parent, AccessibleInfo parentInfo, int index) {
        return offset -> {
            int wanted = index + offset;
            if (wanted < 0 || wanted >= parentInfo.childCount()) return null;
            AcRef sibling = bridge.child(parent, wanted);
            return sibling == null ? null : bridge.info(sibling);
        };
    }

    // ---------------------------------------------------------------- helpers

    private static JabNode node(AccessibleInfo i) {
        if (i == null) return null;
        return new JabNode(i.role(), i.name(), i.description(), i.states(),
                i.x(), i.y(), i.width(), i.height(), i.indexInParent(), i.childCount());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
