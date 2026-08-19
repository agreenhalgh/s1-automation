package com.newbrid.epr_automation.systmone.common.dto;

/**
 * A request for one screenshot from a named worker, sent by the authoring tool over the
 * per-worker control queue (FR-TOOL-02). Workers run no web server, so authoring reaches
 * them the same way jobs do — through the broker.
 *
 * @param requestId  correlation id, echoed back in the {@link CaptureResponse}
 * @param activateWindow bring the worker's SystmOne window to the front before capturing, so
 *                       the author gets the application rather than whatever had focus
 */
public record CaptureRequest(String requestId, boolean activateWindow) {
}
