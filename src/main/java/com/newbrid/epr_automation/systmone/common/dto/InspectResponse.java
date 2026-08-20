package com.newbrid.epr_automation.systmone.common.dto;

import java.util.List;

/**
 * What the worker found under the point the author clicked: the element itself, the chain of
 * containers above it, and a JAB locator that would resolve to it (FR-JAB-02, FR-TOOL-03).
 *
 * <p>{@code matchCount} is the part that decides whether the locator is usable. The engine
 * treats a locator matching more than one element as ambiguous and refuses to act on it
 * (NFR-REL-04), so a suggestion is only worth saving at a count of exactly 1. The worker
 * counts by running the suggested locator over the same tree it just walked, which is the
 * same predicate the JAB backend applies at run time — not an approximation of it.</p>
 *
 * @param element    the deepest element whose bounds contain the point
 * @param ancestors  containers from the window root down to the element's parent, outermost
 *                   first — context for an author deciding whether the element is the one
 *                   they meant
 * @param suggested  a locator that resolves to {@code element}, or null when nothing about
 *                   the element is distinctive enough to write one
 * @param matchCount how many elements {@code suggested} matches: 1 is usable, more is
 *                   ambiguous, 0 means the tree changed under us between walk and count
 * @param error      why nothing was inspected; null on success
 */
public record InspectResponse(
        String requestId,
        String workerId,
        JabNode element,
        List<JabNode> ancestors,
        LocatorDefinition suggested,
        int matchCount,
        String error
) {
    public static InspectResponse failed(String requestId, String workerId, String error) {
        return new InspectResponse(requestId, workerId, null, List.of(), null, 0, error);
    }

    public boolean ok() {
        return error == null && element != null;
    }
}
