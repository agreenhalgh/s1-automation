package com.newbrid.epr_automation.systmone.common.dto;

/**
 * A request to identify the accessible element under one screen point on a named worker,
 * sent by the authoring tool over the same per-worker control queue a capture uses
 * (FR-TOOL-02, FR-JAB-02).
 *
 * <p>{@code kind} is what tells the worker's control listener this is not a capture. A
 * {@link CaptureRequest} carries no such field, so its absence means "capture" and the
 * older message stays valid exactly as it was.</p>
 *
 * <p>Note what this deliberately does <em>not</em> do: activate a window. A capture may
 * bring SystmOne to the front because it is about to photograph it, but an inspection is
 * read against a screenshot the author is already looking at, and rearranging the desktop
 * first would move the very element they clicked on.</p>
 *
 * @param x screen x in the worker's logical pixels — the capture's own coordinate space
 * @param y screen y in the same space
 */
public record InspectRequest(String kind, String requestId, int x, int y) {

    public static final String KIND = "inspect";

    public InspectRequest(String requestId, int x, int y) {
        this(KIND, requestId, x, y);
    }
}
