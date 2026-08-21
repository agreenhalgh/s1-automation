package com.newbrid.epr_automation.systmone.automation.jab;

import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.model.NameMatch;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Whether one accessibility node satisfies one JAB locator.
 *
 * <p>Extracted so that {@link JabBackend}, which resolves locators during a run, and
 * {@link JabInspector}, which suggests them to an author, decide the question with the same
 * code. A tool that judged matches by its own copy of these rules would be worse than no
 * tool: it would certify locators the engine then fails to resolve, and the divergence would
 * show up as a mysterious run failure rather than as a disagreement here.</p>
 */
public final class JabMatching {

    private JabMatching() {}

    /**
     * @param ancestors the element's containers, outermost first — the window it lives in, then
     *                  everything down to its immediate parent. Required, because
     *                  {@code ancestorPath} is decided against it; pass an empty list for an
     *                  element whose chain is not known, and a locator that scopes by ancestor
     *                  will then correctly refuse to match rather than assume.
     */
    public static boolean matches(AccessibleInfo info, List<AccessibleInfo> ancestors, LocatorDefinition l) {
        return matches(info, ancestors, SiblingLookup.NONE, l);
    }

    /**
     * @param siblings reads what sits either side of {@code info}, for {@code previousSibling} and
     *                 {@code followingSibling}. Pass {@link SiblingLookup#NONE} when the caller
     *                 does not track the parent: a locator scoped by a sibling will then refuse to
     *                 match rather than assume, the same way an unknown ancestor chain does
     */
    public static boolean matches(AccessibleInfo info, List<AccessibleInfo> ancestors,
                                  SiblingLookup siblings, LocatorDefinition l) {
        if (info == null || l == null) return false;
        if (l.role() != null && !l.role().equalsIgnoreCase(info.role())) return false;
        if (l.description() != null && !l.description().equalsIgnoreCase(info.description())) return false;
        if (l.index() != null && l.index() != info.indexInParent()) return false;
        if (l.name() != null && !nameMatches(l, info.name())) return false;
        if (l.ancestorPath() != null && !ancestorPathMatches(l.ancestorPath(), ancestors)) return false;
        if (l.parent() != null && !parentMatches(l.parent(), ancestors)) return false;
        if (l.row() != null && l.row() != rowOf(info, ancestors)) return false;
        if (l.column() != null && l.column() != columnOf(info, ancestors)) return false;
        if (l.previousSibling() != null && !siblingMatches(l.previousSibling(), siblings, -1)) return false;
        if (l.followingSibling() != null && !siblingMatches(l.followingSibling(), siblings, 1)) return false;
        // Visible/rendered guard: an element with no bounds cannot be acted on, so it must not
        // count as a match here either — otherwise an off-screen twin would make a good locator
        // look ambiguous.
        return info.width() > 0 && info.height() > 0;
    }

    private static boolean nameMatches(LocatorDefinition l, String actual) {
        if (actual == null) return false;
        NameMatch mode = l.nameMatch() == null ? NameMatch.EXACT : l.nameMatch();
        return switch (mode) {
            case EXACT -> actual.equals(l.name());
            case CONTAINS -> actual.contains(l.name());
            case REGEX -> regexFinds(l.name(), actual);
        };
    }

    /**
     * Whether the element's containers satisfy a {@code >}-separated ancestor path.
     *
     * <p>Segments are {@code role}, {@code role[name]} or {@code [name]}, outermost first, and
     * are matched as a <em>subsequence</em>: {@code "dialog > table"} means "somewhere inside a
     * dialog, somewhere inside a table", not "a table whose parent is a dialog". Real Swing
     * trees are full of layout panels, viewports and scroll panes that no author should have to
     * enumerate, and that a SystmOne release can insert without changing anything visible.</p>
     *
     * <p>The bracketed name is a case-insensitive <em>contains</em>, because dialog titles carry
     * the thing they are asking about — "Confirm deletion of Mr Smith" — and a locator pinned to
     * the whole string would work for exactly one patient.</p>
     */
    static boolean ancestorPathMatches(String path, List<AccessibleInfo> ancestors) {
        List<String> segments = java.util.Arrays.stream(path.split(">"))
                .map(String::trim)
                .filter(segment -> !segment.isEmpty())
                .toList();
        if (segments.isEmpty()) return true;
        if (ancestors == null) return false;

        int next = 0;
        for (AccessibleInfo ancestor : ancestors) {
            if (next < segments.size() && segmentMatches(segments.get(next), ancestor)) next++;
        }
        return next == segments.size();
    }

    /**
     * Whether the element immediately before or after this one matches a {@code role[name]}
     * segment — the same syntax {@code ancestorPath} uses, for one element rather than a chain.
     *
     * <p>This is what identifies a control that has nothing to say about itself by the thing next
     * to it that does: the unlabelled field after the "Surname" label. Without it the only
     * options were {@code index}, which counts every sibling of every kind and shifts whenever
     * the form gains one, or an image anchor.</p>
     *
     * <p><b>Immediately</b> adjacent, deliberately. Unlike {@code ancestorPath}, which skips
     * intervening containers because they are incidental, "the field after this label" is a claim
     * about adjacency and nothing else — searching further for a match could silently pin the
     * locator to a label two fields up and click the wrong control, which is worse than not
     * resolving. A layout strut between label and field will defeat it; {@code index} or an
     * {@code ancestorPath} is the fallback there.</p>
     */
    /**
     * Whether the element's <em>immediate</em> parent matches a {@code role[name]} segment — the
     * same grammar the sibling selectors and each {@code ancestorPath} segment use, with the
     * bracketed name optional.
     *
     * <p>Distinct from a one-segment {@code ancestorPath}, which is satisfied by any ancestor at
     * any depth. This is the narrower claim: "the field whose parent is the Address panel", not
     * "somewhere inside it". Use whichever is true — a path survives the layout gaining a wrapper,
     * a parent pins the level and says so when the structure moves.</p>
     *
     * <p>An element with no known ancestors does not match, the same fail-safe the other scoping
     * fields take: a window has no parent, and an unknown chain is not evidence of one.</p>
     */
    /**
     * Which row of a table this element is in, or -1 if it is not in one.
     *
     * <p>Read from the ancestor chain the walk already carries: the nearest ancestor that is a row
     * knows its own position among its siblings, and that position <em>is</em> the row number. No
     * table API and no extra native calls — the information was already in hand.</p>
     *
     * <p>Only works where the application models rows as nodes. A plain Swing {@code JTable}
     * exposes its cells as a flat list of children with no row nodes at all, and there this
     * returns -1 and a {@code row} locator matches nothing — visibly, rather than by picking the
     * wrong cell. The capture tool's ancestor chain shows which shape a grid has.</p>
     */
    static int rowOf(AccessibleInfo info, List<AccessibleInfo> ancestors) {
        // A row is its own row: "row 2" should address the row itself as readily as a cell in it,
        // and a row node does not appear in its own ancestor chain.
        if (info != null && isRow(info)) return info.indexInParent();
        if (ancestors == null) return -1;
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            if (isRow(ancestors.get(i))) return ancestors.get(i).indexInParent();
        }
        return -1;
    }

    /**
     * Which column of a table this element is in, or -1 if it is not in one.
     *
     * <p>The column is the position of whatever sits <em>directly</em> under the row, which is not
     * always the element itself: a cell that wraps its contents in a panel puts the element one
     * level deeper, where its own index means nothing about the column.</p>
     */
    static int columnOf(AccessibleInfo info, List<AccessibleInfo> ancestors) {
        if (ancestors == null || info == null) return -1;
        // A row spans every column, so it is in none of them: a column on a row locator matches
        // nothing rather than the row's own position among rows.
        if (isRow(info)) return -1;
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            if (!isRow(ancestors.get(i))) continue;
            // The child of the row on the path to this element: the next link in the chain, or the
            // element itself when the row is its parent.
            AccessibleInfo cell = i + 1 < ancestors.size() ? ancestors.get(i + 1) : info;
            return cell.indexInParent();
        }
        return -1;
    }

    /**
     * Whether this node is a table row. Both spellings are accepted because toolkits differ and
     * neither is more correct; anything else is not treated as a row, since guessing would put a
     * click in a cell of something that is not a grid.
     */
    private static boolean isRow(AccessibleInfo info) {
        return "table row".equalsIgnoreCase(info.role()) || "row".equalsIgnoreCase(info.role());
    }

    private static boolean parentMatches(String segment, List<AccessibleInfo> ancestors) {
        if (ancestors == null || ancestors.isEmpty()) return false;
        return segmentMatches(segment, ancestors.get(ancestors.size() - 1));
    }

    private static boolean siblingMatches(String segment, SiblingLookup siblings, int offset) {
        if (siblings == null) return false;
        AccessibleInfo sibling = siblings.at(offset);
        return sibling != null && segmentMatches(segment, sibling);
    }

    private static boolean segmentMatches(String segment, AccessibleInfo ancestor) {
        String role = segment;
        String name = null;
        int open = segment.indexOf('[');
        if (open >= 0 && segment.endsWith("]")) {
            role = segment.substring(0, open).trim();
            name = segment.substring(open + 1, segment.length() - 1).trim();
        }
        if (!role.isEmpty() && !role.equalsIgnoreCase(ancestor.role())) return false;
        if (name == null || name.isEmpty()) return true;
        return ancestor.name() != null && ancestor.name().toLowerCase().contains(name.toLowerCase());
    }

    /**
     * A malformed pattern matches nothing rather than throwing. The engine resolves locators
     * inside a step's retry loop, where an exception would surface as a step failure with a
     * stack trace instead of "this locator did not resolve".
     */
    private static boolean regexFinds(String pattern, String actual) {
        try {
            return Pattern.compile(pattern).matcher(actual).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }
}
