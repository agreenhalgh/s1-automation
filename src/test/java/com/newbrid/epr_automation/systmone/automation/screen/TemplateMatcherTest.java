package com.newbrid.epr_automation.systmone.automation.screen;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the pure-Java template matcher locates a patch and flags ambiguity (FR-SR-02/03). */
class TemplateMatcherTest {

    private final TemplateMatcher matcher = new TemplateMatcher();

    /**
     * High-frequency pseudo-random texture. NCC is invariant to <em>linear</em> intensity
     * changes, so a smooth gradient would correlate ~1.0 everywhere; per-pixel noise gives
     * a texture that only correlates with itself.
     */
    private static BufferedImage patterned(int w, int h, int seed) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, (noise(x, y, seed) << 16) | (noise(x, y, seed + 101) << 8) | noise(x, y, seed + 202));
            }
        }
        return img;
    }

    private static int noise(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 2147483647;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h >>> 16) & 0xFF;
    }

    private static void blit(BufferedImage dst, BufferedImage src, int ox, int oy) {
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                dst.setRGB(ox + x, oy + y, src.getRGB(x, y));
            }
        }
    }

    /**
     * A capture-sized screen of 4px blocks. Block structure matters: a real screen keeps its
     * shape when averaged down, which is what the search relies on, whereas per-pixel noise
     * averages away to nothing and would test a case that never occurs.
     */
    private static BufferedImage blocky(int w, int h, int seed) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = noise(x / 4, y / 4, seed);
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return img;
    }

    @Test
    void findsPatchAtKnownLocation() {
        BufferedImage screen = patterned(120, 90, 3);
        BufferedImage needle = patterned(14, 12, 99);
        blit(screen, needle, 55, 40);

        MatchResult m = matcher.find(screen, needle);

        assertEquals(55, m.x(), "matched X");
        assertEquals(40, m.y(), "matched Y");
        assertTrue(m.score() > 0.99, "exact patch should score ~1.0, was " + m.score());
        assertFalse(m.isAmbiguous(0.03), "single occurrence should be unambiguous");
    }

    @Test
    void detectsAmbiguityWhenPatchAppearsTwice() {
        BufferedImage screen = patterned(160, 120, 5);
        BufferedImage needle = patterned(14, 12, 77);
        blit(screen, needle, 20, 20);
        blit(screen, needle, 110, 80);   // a second, identical occurrence

        MatchResult m = matcher.find(screen, needle);

        assertTrue(m.score() > 0.99);
        assertTrue(m.isAmbiguous(0.03),
                "two identical patches must be flagged ambiguous (best=" + m.score()
                        + ", second=" + m.secondBestScore() + ")");
    }

    @Test
    void rejectsBelowThresholdWhenPatchAbsent() {
        BufferedImage screen = patterned(120, 90, 1);
        BufferedImage needle = patterned(14, 12, 200);   // not present in screen

        MatchResult m = matcher.find(screen, needle);

        // The best correlation of an absent, differently-seeded patch stays well below 0.9.
        assertTrue(m.score() < 0.9, "absent patch should not exceed a normal threshold, was " + m.score());
    }

    /**
     * The case a real bot runs: a full capture, searched at reduced resolution, with the
     * target at an offset that is a multiple of nothing. The search reduces the images to
     * afford the scan, and every position must stay reachable when it does — a target at
     * x=713 must not be found only because 713 happens to divide.
     */
    @Test
    void findsPatchOnAFullSizedCaptureAtAnAwkwardOffset() {
        BufferedImage screen = blocky(1280, 800, 11);
        BufferedImage needle = blocky(64, 28, 909);
        blit(screen, needle, 713, 457);

        MatchResult m = matcher.find(screen, needle);

        assertEquals(713, m.x(), "matched X on a full-sized capture");
        assertEquals(457, m.y(), "matched Y on a full-sized capture");
        assertTrue(m.score() > 0.99, "exact patch should score ~1.0, was " + m.score());
        assertFalse(m.isAmbiguous(0.03), "single occurrence should be unambiguous");
    }

    /**
     * Ambiguity has to survive the reduced-resolution scan too. The runner-up is only
     * comparable to the winner if it is scored at its own peak rather than wherever the
     * scan happened to sample it, so this asserts the runner-up's score, not just the flag.
     */
    @Test
    void detectsAmbiguityOnAFullSizedCapture() {
        BufferedImage screen = blocky(1280, 800, 13);
        BufferedImage needle = blocky(64, 28, 404);
        blit(screen, needle, 301, 145);
        blit(screen, needle, 902, 613);   // a second, identical occurrence

        MatchResult m = matcher.find(screen, needle);

        assertTrue(m.secondBestScore() > 0.99,
                "the runner-up is an identical patch and must be scored at its own peak, was "
                        + m.secondBestScore());
        assertTrue(m.isAmbiguous(0.03),
                "two identical patches must be flagged ambiguous (best=" + m.score()
                        + ", second=" + m.secondBestScore() + ")");
    }

    /**
     * A template that is present exactly once must not lose to something else on screen.
     * The search reduces before it scans, so a competitor that survives reduction better
     * than the target once won outright; the full-resolution re-score is what prevents it.
     */
    @Test
    void prefersTheTrueLocationOverEverythingElseOnAFullSizedCapture() {
        BufferedImage screen = blocky(1280, 800, 17);
        BufferedImage needle = blocky(96, 36, 555);
        blit(screen, needle, 1021, 227);

        MatchResult m = matcher.find(screen, needle);

        assertEquals(1021, m.x());
        assertEquals(227, m.y());
        assertTrue(m.score() - m.secondBestScore() > 0.1,
                "the true location should beat the best competitor clearly (best=" + m.score()
                        + ", second=" + m.secondBestScore() + ")");
    }
}
