package com.newbrid.epr_automation.systmone.automation.screen;

import java.awt.image.BufferedImage;

/**
 * Pure-Java template matching by normalised cross-correlation (NCC) — no native
 * dependency, so it is fully unit-testable (FR-SR-02/03).
 *
 * <p>NCC is invariant to uniform brightness/contrast shifts, which makes it robust to
 * minor rendering differences. A coarse-to-fine search keeps a full-screen match
 * within the NFR-PERF-02 budget: a strided coarse pass locates the region, then a
 * ±stride refinement pass finds the exact pixel. A second coarse pass records the best
 * spatially-distinct competitor so the caller can detect ambiguity.</p>
 */
public class TemplateMatcher {

    /** Locate {@code needle} within {@code haystack}. Returns null if either image is too small. */
    public MatchResult find(BufferedImage haystack, BufferedImage needle) {
        if (haystack == null || needle == null) return null;
        int hw = haystack.getWidth(), hh = haystack.getHeight();
        int nw = needle.getWidth(), nh = needle.getHeight();
        if (nw > hw || nh > hh || nw == 0 || nh == 0) return null;

        float[] H = toGray(haystack);
        float[] N = toGray(needle);

        // Pre-compute the needle's zero-mean form and its L2 norm (constant across the scan).
        double nMean = mean(N);
        double nNorm = 0;
        float[] nZero = new float[N.length];
        for (int i = 0; i < N.length; i++) {
            nZero[i] = (float) (N[i] - nMean);
            nNorm += nZero[i] * nZero[i];
        }
        nNorm = Math.sqrt(nNorm);
        if (nNorm == 0) return null; // flat template — cannot correlate

        int maxX = hw - nw, maxY = hh - nh;
        // Full scan for modest search areas (exact peak, no chance of striding past it);
        // coarse stride only pays off on large full-screen captures, whose real UI content
        // is locally smooth enough for coarse-to-fine to converge.
        int stride = (hw * hh <= 250_000) ? 1 : Math.max(2, Math.min(8, Math.min(nw, nh) / 4));

        // Coarse pass: best and best-distinct-competitor.
        double bestScore = -2, secondScore = -2;
        int bestX = 0, bestY = 0, secX = -9999, secY = -9999;
        int minSep = Math.max(nw, nh) / 2 + 1;
        for (int y = 0; y <= maxY; y += stride) {
            for (int x = 0; x <= maxX; x += stride) {
                double s = ncc(H, hw, nZero, nw, nh, x, y, nNorm);
                if (s > bestScore) {
                    // demote the old best to competitor if it is far enough away
                    if (far(bestX, bestY, x, y, minSep) && bestScore > secondScore) {
                        secondScore = bestScore; secX = bestX; secY = bestY;
                    }
                    bestScore = s; bestX = x; bestY = y;
                } else if (s > secondScore && far(x, y, bestX, bestY, minSep)) {
                    secondScore = s; secX = x; secY = y;
                }
            }
        }

        // Fine pass: refine the best within one coarse cell.
        int loX = Math.max(0, bestX - stride), hiX = Math.min(maxX, bestX + stride);
        int loY = Math.max(0, bestY - stride), hiY = Math.min(maxY, bestY + stride);
        for (int y = loY; y <= hiY; y++) {
            for (int x = loX; x <= hiX; x++) {
                double s = ncc(H, hw, nZero, nw, nh, x, y, nNorm);
                if (s > bestScore) { bestScore = s; bestX = x; bestY = y; }
            }
        }
        return new MatchResult(bestX, bestY, nw, nh, bestScore, secondScore);
    }

    private static boolean far(int ax, int ay, int bx, int by, int minSep) {
        return Math.abs(ax - bx) >= minSep || Math.abs(ay - by) >= minSep;
    }

    /** Normalised cross-correlation of the needle placed at (ox, oy) in the haystack. */
    private static double ncc(float[] H, int hw, float[] nZero, int nw, int nh, int ox, int oy, double nNorm) {
        // window mean
        double sum = 0;
        for (int j = 0; j < nh; j++) {
            int row = (oy + j) * hw + ox;
            for (int i = 0; i < nw; i++) sum += H[row + i];
        }
        double hMean = sum / (nw * (double) nh);

        double dot = 0, hNorm = 0;
        int k = 0;
        for (int j = 0; j < nh; j++) {
            int row = (oy + j) * hw + ox;
            for (int i = 0; i < nw; i++) {
                double hz = H[row + i] - hMean;
                dot += hz * nZero[k++];
                hNorm += hz * hz;
            }
        }
        hNorm = Math.sqrt(hNorm);
        if (hNorm == 0) return 0;
        return dot / (hNorm * nNorm);
    }

    private static float[] toGray(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        float[] g = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, gr = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                g[y * w + x] = 0.299f * r + 0.587f * gr + 0.114f * b;
            }
        }
        return g;
    }

    private static double mean(float[] a) {
        double s = 0;
        for (float v : a) s += v;
        return s / a.length;
    }
}
