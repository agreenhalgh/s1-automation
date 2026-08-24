package com.newbrid.epr_automation.systmone.common.dto;

import com.newbrid.epr_automation.systmone.common.model.LocatorType;
import com.newbrid.epr_automation.systmone.common.model.NameMatch;

/**
 * A single locator within a step's ordered fallback chain (FR-ENG-02).
 *
 * <p>One record carries both strategies' fields; {@link #type} discriminates.
 * The unused fields are simply {@code null}. Use the {@link #jab} / {@link #image}
 * factories to build them.</p>
 *
 * <h4>JAB fields</h4> role, name (+ nameMatch), description, value, index, ancestorPath,
 * parent, previousSibling, followingSibling, row, column.
 * <h4>IMAGE fields</h4> templateRef, threshold, and optional anchor-relative locating
 * (anchorTemplateRef + offsetX/offsetY) per FR-SR-07.
 */
public record LocatorDefinition(
        LocatorType type,
        // --- JAB ---
        String role,
        String name,
        NameMatch nameMatch,
        String description,
        String value,
        Integer index,
        String ancestorPath,
        String parent,
        String previousSibling,
        String followingSibling,
        String row,
        String column,
        // --- IMAGE ---
        String templateRef,
        Double threshold,
        String anchorTemplateRef,
        Integer offsetX,
        Integer offsetY
) {
    public LocatorDefinition {
        // Blank is not a constraint anyone means to express, and a blank sibling selector would
        // reject every element rather than be ignored.
        ancestorPath = blankToNull(ancestorPath);
        parent = blankToNull(parent);
        previousSibling = blankToNull(previousSibling);
        followingSibling = blankToNull(followingSibling);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * Every field this locator actually sets, for a log or a diagnostic.
     *
     * <p>Only the set fields appear, because a locator's problem is usually one of the fields it
     * <em>does</em> declare, and a line of nulls hides it. Deliberately not used for the
     * {@code resolvedLocator} recorded on a step report: that column is 255 characters and an
     * {@code ancestorPath} can be longer than that on its own.</p>
     */
    public String describe() {
        StringBuilder sb = new StringBuilder(type == null ? "?" : type.name()).append('[');
        int start = sb.length();
        append(sb, "role", role);
        append(sb, "name", name);
        if (name != null) append(sb, "nameMatch", nameMatch == null ? NameMatch.EXACT : nameMatch);
        append(sb, "description", description);
        append(sb, "value", value);
        append(sb, "index", index);
        append(sb, "ancestorPath", ancestorPath);
        append(sb, "parent", parent);
        append(sb, "previousSibling", previousSibling);
        append(sb, "followingSibling", followingSibling);
        append(sb, "row", row);
        append(sb, "column", column);
        append(sb, "templateRef", templateRef);
        append(sb, "threshold", threshold);
        append(sb, "anchorTemplateRef", anchorTemplateRef);
        append(sb, "offsetX", offsetX);
        append(sb, "offsetY", offsetY);
        if (sb.length() == start) sb.append("no fields set");
        return sb.append(']').toString();
    }

    private static void append(StringBuilder sb, String field, Object value) {
        if (value == null) return;
        if (sb.charAt(sb.length() - 1) != '[') sb.append(", ");
        sb.append(field).append('=');
        if (value instanceof String s) {
            sb.append('"').append(s).append('"');
        } else {
            sb.append(value);
        }
    }

    public static LocatorDefinition jab(String role, String name, NameMatch nameMatch) {
        return new LocatorDefinition(LocatorType.JAB, role, name,
                nameMatch == null ? NameMatch.EXACT : nameMatch,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static LocatorDefinition jab(String role, String name, NameMatch nameMatch,
                                        String description, Integer index, String ancestorPath) {
        return jab(role, name, nameMatch, description, index, ancestorPath, null, null);
    }

    /** A JAB locator scoped by what sits either side of it — see {@code previousSibling}. */
    public static LocatorDefinition jab(String role, String name, NameMatch nameMatch,
                                        String description, Integer index, String ancestorPath,
                                        String previousSibling, String followingSibling) {
        return new LocatorDefinition(LocatorType.JAB, role, name,
                nameMatch == null ? NameMatch.EXACT : nameMatch,
                description, null, index, ancestorPath, null, previousSibling, followingSibling,
                null, null, null, null, null, null, null);
    }

    /**
     * The same locator, scoped to an immediate parent. A wither rather than another factory
     * overload: the JAB fields are combinable, and a factory per combination is a factory per
     * pair of them.
     */
    public LocatorDefinition withParent(String parent) {
        return new LocatorDefinition(type, role, name, nameMatch, description, value, index,
                ancestorPath, parent, previousSibling, followingSibling, row, column, templateRef,
                threshold, anchorTemplateRef, offsetX, offsetY);
    }

    /**
     * The row this locator asks for, as a number, or null when it asks for none — and also null
     * when it asks for one that is not a number, which happens when a {@code ${var}} was never
     * resolved. Matching treats that as no match rather than as no constraint: a locator that
     * meant "row 4" must not quietly become "any row".
     */
    public Integer rowValue() {
        return asNumber(row);
    }

    public Integer columnValue() {
        return asNumber(column);
    }

    private static Integer asNumber(String value) {
        if (value == null) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The same locator with every string field passed through {@code substitute} — how
     * {@code ${var}} placeholders reach a locator.
     *
     * <p>Every string field, not a chosen few: an author who can write {@code ${patientName}} as a
     * name will reasonably expect it to work in a description or an ancestor path too, and a
     * substitution that applies to some fields and not others is the kind of rule nobody
     * remembers.</p>
     */
    public LocatorDefinition resolvedWith(java.util.function.UnaryOperator<String> substitute) {
        return new LocatorDefinition(type, substitute.apply(role), substitute.apply(name), nameMatch,
                substitute.apply(description), substitute.apply(value), index,
                substitute.apply(ancestorPath), substitute.apply(parent),
                substitute.apply(previousSibling), substitute.apply(followingSibling),
                substitute.apply(row), substitute.apply(column), substitute.apply(templateRef),
                threshold, substitute.apply(anchorTemplateRef), offsetX, offsetY);
    }

    /**
     * The same locator, addressed by position within a table. Both are zero-based, and a header
     * row usually occupies row 0 — so the first row of data is normally row 1.
     */
    public LocatorDefinition withCell(Integer row, Integer column) {
        return withCellText(row == null ? null : row.toString(),
                column == null ? null : column.toString());
    }

    /** As above, but with the raw text — which may be a {@code ${var}} rather than a number. */
    public LocatorDefinition withCellText(String row, String column) {
        return new LocatorDefinition(type, role, name, nameMatch, description, value, index,
                ancestorPath, parent, previousSibling, followingSibling, row, column, templateRef,
                threshold, anchorTemplateRef, offsetX, offsetY);
    }

    public static LocatorDefinition image(String templateRef, double threshold) {
        return new LocatorDefinition(LocatorType.IMAGE, null, null, null, null, null, null, null,
                null, null, null, null, null, templateRef, threshold, null, null, null);
    }

    public static LocatorDefinition imageAnchored(String templateRef, double threshold,
                                                  String anchorTemplateRef, int offsetX, int offsetY) {
        return new LocatorDefinition(LocatorType.IMAGE, null, null, null, null, null, null, null,
                null, null, null, null, null, templateRef, threshold, anchorTemplateRef,
                offsetX, offsetY);
    }
}
