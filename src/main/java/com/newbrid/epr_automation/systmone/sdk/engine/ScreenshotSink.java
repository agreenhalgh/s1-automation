package com.newbrid.epr_automation.systmone.sdk.engine;

import java.awt.image.BufferedImage;

/**
 * Sink that persists a failure screenshot (NFR-OBS-02) and returns a reference the
 * control plane can resolve. Subject to the PID redaction/retention rules in §5.4 —
 * the implementation, not the engine, is responsible for redaction and encryption.
 */
@FunctionalInterface
public interface ScreenshotSink {

    /** Store the image and return an opaque reference, or {@code null} if not stored. */
    String store(String runId, int stepIndex, BufferedImage image);

    ScreenshotSink NONE = (runId, stepIndex, image) -> null;
}
