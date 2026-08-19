package com.newbrid.epr_automation.systmone.automation.screen;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Pure-Java template matching by normalised cross-correlation (NCC) — no native
 * dependency, so it is fully unit-testable (FR-SR-02/03).
 *
 * <p>NCC is invariant to uniform brightness/contrast shifts, which makes it robust to
 * minor rendering differences. Scoring every pixel of a full-screen capture at full
 * resolution is far too slow, so the search runs on a reduced copy of both images —
 * <em>averaged</em> down, not sampled down. The distinction is the whole point: sampling
 * every n-th pixel aliases against text and one-pixel borders, and an earlier version of
 * this class did exactly that and routinely walked past the true peak onto an unrelated
 * control.</p>
 *
 * <p>Reducing by a factor of n also fixes the search to positions that are multiples of n,
 * and a control is under no obligation to sit on one. Every phase offset of the template is
 * therefore searched, which restores full-resolution coverage: a template whose true home is
 * at x=1023 is found by the phase that shifts it onto the grid. The candidates that survive
 * are then re-scored at full resolution, so nothing is decided on reduced-resolution
 * evidence.</p>
 *
 * <p>Several peaks, not one, are carried through, and every one of them is re-scored. That
 * is what makes the runner-up comparable to the winner, and therefore what makes {@link
 * MatchResult#isAmbiguous(double)} mean anything: two identical controls have to be measured
 * at their own peaks before their scores can be compared.</p>
 */
public class TemplateMatcher {

    /** Peaks carried to the full-resolution pass. More costs little; too few loses the target. */
    private static final int CANDIDATES = 48;
    /** The template must stay at least this wide and tall once reduced, or it carries no shape. */
    private static final int MIN_REDUCED_DIM = 6;
    /** Largest reduction factor, as a power of two. Beyond this the template blurs away. */
    private static final int MAX_REDUCTION_SHIFT = 3;
    /** ± full-resolution search radius around each candidate. Covers block-averaging slack. */
    private static final int REFINE_RADIUS = 2;
    /** Search areas at or below this are scanned at full resolution — reduction saves nothing. */
    private static final int EXHAUSTIVE_AREA = 250_000;

    /** Locate {@code needle} within {@code haystack}. Returns null if either image is too small. */
    public MatchResult find(BufferedImage haystack, BufferedImage needle) {
        if (haystack == null || needle == null) return null;
        int hw = haystack.getWidth(), hh = haystack.getHeight();
        int nw = needle.getWidth(), nh = needle.getHeight();
        if (nw > hw || nh > hh || nw == 0 || nh == 0) return null;

        Plane screen = new Plane(toGray(haystack), hw, hh);
        Plane template = new Plane(toGray(needle), nw, nh);
        if (template.isFlat()) return null; // flat template — cannot correlate

        int minSep = Math.max(nw, nh) / 2 + 1;
        PeakSet peaks = new PeakSet(CANDIDATES, minSep);
        int factor = reduction(hw, hh, nw, nh);

        if (factor == 1) {
            Level level = new Level(screen, template);
            for (int y = 0; y <= level.maxY; y++) {
                for (int x = 0; x <= level.maxX; x++) peaks.offer(x, y, level.score(x, y));
            }
        } else {
            search(screen, template, factor, peaks);
        }

        int count = peaks.size();
        if (count == 0) return null;

        int[] px = peaks.xs(), py = peaks.ys();
        double[] ps = peaks.scores();
        if (factor > 1) {
            // Nothing is decided on reduced-resolution evidence: every surviving candidate is
            // re-scored where it actually lies, so the winner and the runner-up are comparable.
            Level full = new Level(screen, template);
            for (int i = 0; i < count; i++) {
                Peak best = full.bestAround(px[i], py[i], REFINE_RADIUS);
                px[i] = best.x(); py[i] = best.y(); ps[i] = best.score();
            }
        }

        int bi = 0;
        for (int i = 1; i < count; i++) if (ps[i] > ps[bi]) bi = i;

        // The runner-up is the best score at a location that could not be confused with the
        // winner — the same separation the caller's ambiguity margin is defined against.
        double second = -2;
        for (int i = 0; i < count; i++) {
            if (i != bi && far(px[i], py[i], px[bi], py[bi], minSep) && ps[i] > second) second = ps[i];
        }
        return new MatchResult(px[bi], py[bi], nw, nh, ps[bi], second);
    }

    /**
     * Scan the reduced screen once per phase offset of the template, recording candidates in
     * full-resolution coordinates. The phases together cover every full-resolution position:
     * a template placed at x lines up with reduced column q when {@code x = factor*q - phase},
     * and phase runs over the whole factor.
     */
    private static void search(Plane screen, Plane template, int factor, PeakSet peaks) {
        Plane reducedScreen = reduce(screen, factor, 0, 0);
        // Each phase covers a disjoint set of positions, so they can run at once. They are
        // merged back in phase order, never completion order, so the same screenshot and the
        // same template always produce the same match.
        List<PeakSet> perPhase = IntStream.range(0, factor * factor)
                .parallel()
                .mapToObj(phase -> scanPhase(reducedScreen, template, factor,
                        phase % factor, phase / factor, peaks.capacity, peaks.minSep))
                .toList();
        for (PeakSet phase : perPhase) {
            if (phase != null) phase.drainInto(peaks);
        }
    }

    private static PeakSet scanPhase(Plane reducedScreen, Plane template, int factor,
                                     int phaseX, int phaseY, int capacity, int minSep) {
        Plane reducedTemplate = reduce(template, factor, phaseX, phaseY);
        if (reducedTemplate == null) return null;
        Level level = new Level(reducedScreen, reducedTemplate);
        PeakSet found = new PeakSet(capacity, minSep);
        for (int y = 0; y <= level.maxY; y++) {
            for (int x = 0; x <= level.maxX; x++) {
                found.offer(x * factor - phaseX, y * factor - phaseY, level.score(x, y));
            }
        }
        return found;
    }

    /**
     * How far the images can be reduced before the template stops carrying shape. Small search
     * areas are not reduced at all — the saving is not worth any loss of detail.
     */
    private static int reduction(int hw, int hh, int nw, int nh) {
        if ((long) hw * hh <= EXHAUSTIVE_AREA) return 1;
        int shift = 0;
        int dim = Math.min(nw, nh);
        while (shift < MAX_REDUCTION_SHIFT && dim / 2 >= MIN_REDUCED_DIM) {
            dim /= 2;
            shift++;
        }
        return 1 << shift;
    }

    private static boolean far(int ax, int ay, int bx, int by, int minSep) {
        return Math.abs(ax - bx) >= minSep || Math.abs(ay - by) >= minSep;
    }

    // ---------------------------------------------------------------- pixels

    /** A greyscale image plane. */
    private record Plane(float[] px, int w, int h) {
        boolean isFlat() {
            float first = px[0];
            for (float v : px) if (v != first) return false;
            return true;
        }
    }

    /**
     * Average {@code factor}x{@code factor} blocks, starting {@code phase} pixels in. Whole
     * blocks only: a trailing partial block is dropped rather than averaged over fewer pixels,
     * which would make that column or row disagree with the screen's own blocks.
     */
    private static Plane reduce(Plane in, int factor, int phaseX, int phaseY) {
        int w = (in.w() - phaseX) / factor, h = (in.h() - phaseY) / factor;
        if (w < 1 || h < 1) return null;
        float[] out = new float[w * h];
        float scale = 1f / (factor * factor);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0;
                int originY = phaseY + y * factor, originX = phaseX + x * factor;
                for (int j = 0; j < factor; j++) {
                    int row = (originY + j) * in.w() + originX;
                    for (int i = 0; i < factor; i++) sum += in.px()[row + i];
                }
                out[y * w + x] = sum * scale;
            }
        }
        return new Plane(out, w, h);
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

    // ---------------------------------------------------------------- scoring

    private record Peak(int x, int y, double score) {}

    /**
     * NCC of one template against one screen, at one resolution.
     *
     * <p>The template is held zero-mean, which drops the window mean out of the numerator
     * altogether: a zero-mean template sums to nothing, so subtracting a constant from the
     * window changes no product. What is left to compute is the window's own sum and
     * sum-of-squares, which integral images answer in constant time — so a position costs one
     * dot product instead of three passes over the window.</p>
     */
    private static final class Level {
        private final Plane screen;
        private final int nw, nh;
        final int maxX, maxY;
        private final float[] zeroMean;
        private final double norm;
        private final double[] sum, sumSq;   // integral images, (w+1) x (h+1)
        private final int iw;

        Level(Plane screen, Plane template) {
            this.screen = screen;
            this.nw = template.w();
            this.nh = template.h();
            this.maxX = screen.w() - nw;
            this.maxY = screen.h() - nh;

            double mean = 0;
            for (float v : template.px()) mean += v;
            mean /= template.px().length;
            this.zeroMean = new float[template.px().length];
            double n = 0;
            for (int i = 0; i < template.px().length; i++) {
                zeroMean[i] = (float) (template.px()[i] - mean);
                n += zeroMean[i] * zeroMean[i];
            }
            this.norm = Math.sqrt(n);

            this.iw = screen.w() + 1;
            this.sum = new double[iw * (screen.h() + 1)];
            this.sumSq = new double[iw * (screen.h() + 1)];
            for (int y = 0; y < screen.h(); y++) {
                double rowSum = 0, rowSqSum = 0;
                for (int x = 0; x < screen.w(); x++) {
                    float v = screen.px()[y * screen.w() + x];
                    rowSum += v;
                    rowSqSum += (double) v * v;
                    sum[(y + 1) * iw + x + 1] = sum[y * iw + x + 1] + rowSum;
                    sumSq[(y + 1) * iw + x + 1] = sumSq[y * iw + x + 1] + rowSqSum;
                }
            }
        }

        double score(int ox, int oy) {
            if (norm == 0) return 0;
            double area = nw * (double) nh;
            double s = box(sum, ox, oy);
            double variance = box(sumSq, ox, oy) - s * s / area;
            if (variance <= 1e-6) return 0;   // flat window — nothing to correlate with

            double dot = 0;
            int k = 0;
            for (int j = 0; j < nh; j++) {
                int row = (oy + j) * screen.w() + ox;
                for (int i = 0; i < nw; i++) dot += screen.px()[row + i] * zeroMean[k++];
            }
            return dot / (Math.sqrt(variance) * norm);
        }

        private double box(double[] integral, int ox, int oy) {
            int x1 = ox + nw, y1 = oy + nh;
            return integral[y1 * iw + x1] - integral[oy * iw + x1]
                    - integral[y1 * iw + ox] + integral[oy * iw + ox];
        }

        /** Best position within {@code radius} of (cx, cy), clamped to the search area. */
        Peak bestAround(int cx, int cy, int radius) {
            int loX = Math.max(0, Math.min(maxX, cx - radius));
            int hiX = Math.max(0, Math.min(maxX, cx + radius));
            int loY = Math.max(0, Math.min(maxY, cy - radius));
            int hiY = Math.max(0, Math.min(maxY, cy + radius));
            double best = -2;
            int bx = loX, by = loY;
            for (int y = loY; y <= hiY; y++) {
                for (int x = loX; x <= hiX; x++) {
                    double s = score(x, y);
                    if (s > best) { best = s; bx = x; by = y; }
                }
            }
            return new Peak(bx, by, best);
        }
    }

    // ---------------------------------------------------------------- candidates

    /**
     * The strongest peaks seen so far, held at least {@code minSep} apart so the set covers
     * distinct locations rather than clustering around one control. Non-maximum suppression
     * happens on insert: a position near an existing peak replaces it when it scores better,
     * instead of crowding its neighbours out of the set.
     */
    private static final class PeakSet {
        private final int capacity, minSep;
        private final int[] xs, ys;
        private final double[] scores;
        private int size;

        PeakSet(int capacity, int minSep) {
            this.capacity = capacity;
            this.minSep = minSep;
            this.xs = new int[capacity];
            this.ys = new int[capacity];
            this.scores = new double[capacity];
        }

        void offer(int x, int y, double score) {
            int weakest = -1;
            for (int i = 0; i < size; i++) {
                if (!far(xs[i], ys[i], x, y, minSep)) {
                    if (score > scores[i]) { xs[i] = x; ys[i] = y; scores[i] = score; }
                    return;   // same neighbourhood — never occupies two slots
                }
                if (weakest < 0 || scores[i] < scores[weakest]) weakest = i;
            }
            if (size < capacity) {
                xs[size] = x; ys[size] = y; scores[size] = score; size++;
            } else if (score > scores[weakest]) {
                xs[weakest] = x; ys[weakest] = y; scores[weakest] = score;
            }
        }

        /** Feed every peak held here into {@code target}, which applies its own suppression. */
        void drainInto(PeakSet target) {
            for (int i = 0; i < size; i++) target.offer(xs[i], ys[i], scores[i]);
        }

        int size() { return size; }
        int[] xs() { return xs; }
        int[] ys() { return ys; }
        double[] scores() { return scores; }
    }
}
