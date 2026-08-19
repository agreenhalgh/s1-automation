package com.newbrid.epr_automation.systmone.automation.screen;

import com.newbrid.epr_automation.systmone.sdk.locator.ElementHandle;

import java.awt.Rectangle;

/**
 * {@link ElementHandle} for a screen-recognition match.
 *
 * @param matchScale the factor the template was scaled by to match this capture — 1.0
 *                   normally, and 1/uiScale when the template was cropped at a different
 *                   desktop scaling than this worker captures. Step offsets are measured
 *                   on the author's screenshot, so they are scaled by the same factor.
 */
public record ImageElementHandle(Rectangle screenBounds, double score, double matchScale)
        implements ElementHandle {

    public ImageElementHandle(Rectangle screenBounds, double score) {
        this(screenBounds, score, 1.0);
    }

    @Override
    public String description() {
        String at = String.format("image@[%d,%d %dx%d] score=%.3f",
                screenBounds.x, screenBounds.y, screenBounds.width, screenBounds.height, score);
        return matchScale == 1.0 ? at : at + String.format(" scale=%.2f", matchScale);
    }
}
