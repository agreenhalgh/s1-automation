package com.newbrid.epr_automation.systmone.automation.screen;

import java.awt.Rectangle;

/**
 * Result of a template match: the best location and its normalised cross-correlation
 * score in [-1, 1], plus the best <em>competing</em> score elsewhere on screen so the
 * caller can reject ambiguous matches (fail-safe, NFR-REL-04).
 */
public record MatchResult(int x, int y, int width, int height, double score, double secondBestScore) {

    public Rectangle rectangle() {
        return new Rectangle(x, y, width, height);
    }

    /** Centre point of the matched region. */
    public java.awt.Point center() {
        return new java.awt.Point(x + width / 2, y + height / 2);
    }

    /**
     * A match is ambiguous when a second, spatially distinct location scores almost as
     * well — we cannot be sure which is the intended target, so we must not act.
     */
    public boolean isAmbiguous(double margin) {
        return secondBestScore >= score - margin;
    }
}
