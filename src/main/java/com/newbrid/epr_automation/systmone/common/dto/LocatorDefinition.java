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
 * <h4>JAB fields</h4> role, name (+ nameMatch), description, value, index, ancestorPath.
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
        // --- IMAGE ---
        String templateRef,
        Double threshold,
        String anchorTemplateRef,
        Integer offsetX,
        Integer offsetY
) {
    public static LocatorDefinition jab(String role, String name, NameMatch nameMatch) {
        return new LocatorDefinition(LocatorType.JAB, role, name,
                nameMatch == null ? NameMatch.EXACT : nameMatch,
                null, null, null, null, null, null, null, null, null);
    }

    public static LocatorDefinition jab(String role, String name, NameMatch nameMatch,
                                        String description, Integer index, String ancestorPath) {
        return new LocatorDefinition(LocatorType.JAB, role, name,
                nameMatch == null ? NameMatch.EXACT : nameMatch,
                description, null, index, ancestorPath, null, null, null, null, null);
    }

    public static LocatorDefinition image(String templateRef, double threshold) {
        return new LocatorDefinition(LocatorType.IMAGE, null, null, null, null, null, null, null,
                templateRef, threshold, null, null, null);
    }

    public static LocatorDefinition imageAnchored(String templateRef, double threshold,
                                                  String anchorTemplateRef, int offsetX, int offsetY) {
        return new LocatorDefinition(LocatorType.IMAGE, null, null, null, null, null, null, null,
                templateRef, threshold, anchorTemplateRef, offsetX, offsetY);
    }
}
