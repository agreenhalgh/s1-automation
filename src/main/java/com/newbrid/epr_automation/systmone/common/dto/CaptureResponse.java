package com.newbrid.epr_automation.systmone.common.dto;

/**
 * One screenshot from a worker, with the display geometry a template author needs.
 *
 * <p>{@code uiScale} is not decoration: Robot captures the <em>logical</em> desktop, so a
 * template cropped from this image is in logical pixels and matches only when the same
 * correction is applied at run time. Capturing through the worker — rather than the author's
 * own screen — is what makes a template right by construction.</p>
 *
 * @param imageBase64 PNG bytes, base64-encoded; null when {@code error} is set
 * @param error       why no image was captured (a headless worker session, typically)
 */
public record CaptureResponse(
        String requestId,
        String workerId,
        int width,
        int height,
        double uiScale,
        String windowTitle,
        String imageBase64,
        String error
) {
    public static CaptureResponse failed(String requestId, String workerId, String error) {
        return new CaptureResponse(requestId, workerId, 0, 0, 1.0, null, null, error);
    }

    public boolean ok() {
        return error == null && imageBase64 != null;
    }
}
