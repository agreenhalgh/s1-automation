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
        if (info == null || l == null) return false;
        if (l.role() != null && !l.role().equalsIgnoreCase(info.role())) return false;
        if (l.description() != null && !l.description().equalsIgnoreCase(info.description())) return false;
        if (l.index() != null && l.index() != info.indexInParent()) return false;
        if (l.name() != null && !nameMatches(l, info.name())) return false;
        if (l.ancestorPath() != null && !ancestorPathMatches(l.ancestorPath(), ancestors)) return false;
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
