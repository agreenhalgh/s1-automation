package com.newbrid.epr_automation.systmone.automation.screen;

import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime counterpart to registration-time validation: if a template reference still fails to
 * resolve on a worker — deleted, or stored bytes that are not a readable image — the step must
 * fail with a message that names the reference, never act on a guessed region.
 */
class ScreenBackendTemplateRefTest {

    /** The anchor patch, and a screen with it blitted in at a known spot so NCC scores ~1.0. */
    private static final BufferedImage ANCHOR = patterned(14, 12, 99);
    private static final BufferedImage SCREEN = screenContaining(ANCHOR, 40, 25);

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

    private static BufferedImage screenContaining(BufferedImage patch, int ox, int oy) {
        BufferedImage screen = patterned(120, 90, 3);
        for (int y = 0; y < patch.getHeight(); y++) {
            for (int x = 0; x < patch.getWidth(); x++) {
                screen.setRGB(ox + x, oy + y, patch.getRGB(x, y));
            }
        }
        return screen;
    }

    private ScreenBackend backendWith(Map<String, BufferedImage> store) {
        RobotInput input = new RobotInput() {
            @Override public boolean isAvailable() { return true; }
        };
        return new ScreenBackend(store::get, input, () -> SCREEN, 0.03);
    }

    @Test
    void namesAnUnresolvedTemplateRef() {
        ScreenBackend backend = backendWith(Map.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                backend.resolve(LocatorDefinition.image("search-button", 0.9), Duration.ofMillis(50)));

        assertTrue(ex.getMessage().contains("templateRef 'search-button'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("did not resolve to a template image"), ex.getMessage());
    }

    @Test
    void namesAnUnresolvedAnchorTemplateRef() {
        ScreenBackend backend = backendWith(Map.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                backend.resolve(LocatorDefinition.imageAnchored(null, 0.9, "grid-header", 0, 30),
                        Duration.ofMillis(50)));

        assertTrue(ex.getMessage().contains("anchorTemplateRef 'grid-header'"), ex.getMessage());
    }

    /**
     * The sizing template of an anchored locator used to be optional: a missing one left a 1x1
     * region, so the click landed on the raw offset instead of the control's centre — silently
     * off-target. It must fail instead. Note this check is only reached once the anchor itself
     * matches, hence the anchor is present in the store and on screen here.
     */
    @Test
    void namesAnUnresolvedSizingTemplateInsteadOfClickingA1x1Region() {
        ScreenBackend backend = backendWith(Map.of("grid-header", ANCHOR));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                backend.resolve(LocatorDefinition.imageAnchored("row-strip", 0.9, "grid-header", 0, 30),
                        Duration.ofMillis(50)));

        assertTrue(ex.getMessage().contains("templateRef 'row-strip'"), ex.getMessage());
    }
}
