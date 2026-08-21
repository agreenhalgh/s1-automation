package com.newbrid.epr_automation.systmone.automation.screen;

import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.model.LocatorType;
import com.newbrid.epr_automation.systmone.sdk.locator.ElementHandle;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A template cropped with a native screenshot tool on a scaled display is larger than
 * the same control in a Robot capture by exactly the scale factor, and NCC has no scale
 * invariance — so without correction the locator can never match, whatever the threshold.
 * Reproduced here at 200%, the case that broke `patient-registration-search` on a
 * 2880x1800 panel captured as 1440x900.
 */
class ScreenBackendScalingTest {

    private static final int SCALE = 2;
    private static final int PATCH_X = 300;
    private static final int PATCH_Y = 180;

    /** The control as it appears in the logical capture. */
    private static final BufferedImage PATCH = patterned(60, 24, 7);

    /** The whole logical-resolution capture, with the control blitted in at a known spot. */
    private static final BufferedImage CAPTURE = screenContaining(PATCH, PATCH_X, PATCH_Y);

    /** What Snipping Tool hands you on a 200% display: the same control, twice the size. */
    private static final BufferedImage NATIVE_CROP = resize(PATCH, SCALE);

    private ScreenBackend backend(Map<String, BufferedImage> templates, double uiScale) {
        RobotInput input = new RobotInput() {
            @Override public boolean isAvailable() { return true; }
            @Override public double uiScale() { return uiScale; }
        };
        return new ScreenBackend(templates::get, input, () -> CAPTURE, 0.03);
    }

    private static LocatorDefinition image(String ref, double threshold) {
        return LocatorDefinition.image(ref, threshold);
    }

    @Test
    void aTemplateCroppedAtDoubleScaleStillResolves() {
        ScreenBackend backend = backend(Map.of("search", NATIVE_CROP), 2.0);

        Optional<ElementHandle> hit = backend.resolve(image("search", 0.90), Duration.ofMillis(100));

        assertTrue(hit.isPresent(), "the 2x template should be matched after rescaling");
        Rectangle b = hit.get().screenBounds();
        assertEquals(PATCH_X, b.x, 2, "matched at the control's real position");
        assertEquals(PATCH_Y, b.y, 2);
        assertEquals(PATCH.getWidth(), b.width, 2, "bounds are in capture pixels, not template pixels");
        assertEquals(0.5, ((ImageElementHandle) hit.get()).matchScale(), 1e-9);
    }

    @Test
    void theSameTemplateCannotMatchWhenTheDesktopIsUnscaled() {
        // Guards the premise: without the correction this is simply unmatchable, which is
        // why the original failure looked like a bad template rather than a bad scale.
        ScreenBackend backend = backend(Map.of("search", NATIVE_CROP), 1.0);

        assertTrue(backend.resolve(image("search", 0.90), Duration.ofMillis(50)).isEmpty());
    }

    @Test
    void aCorrectlyCroppedTemplateIsUnaffected() {
        ScreenBackend backend = backend(Map.of("search", PATCH), 2.0);

        Optional<ElementHandle> hit = backend.resolve(image("search", 0.90), Duration.ofMillis(100));

        assertTrue(hit.isPresent());
        assertEquals(1.0, ((ImageElementHandle) hit.get()).matchScale(), 1e-9,
                "a template already in capture space must match 1:1, not be rescaled");
    }

    @Test
    void anchorOffsetsScaleWithTheTemplateTheyWereMeasuredOn() {
        // The author measured +150,+25 on their 2x screenshot, so on this capture the
        // field sits +75,+12 from the anchor. Scaling the offset with the template is
        // what keeps one bot definition working across differently scaled workstations.
        ScreenBackend backend = backend(Map.of("anchor", NATIVE_CROP), 2.0);
        // No sizing template: the target is the point the offset lands on, not a region.
        LocatorDefinition anchored = LocatorDefinition.imageAnchored(null, 0.90, "anchor", 150, 25);

        Optional<ElementHandle> hit = backend.resolve(anchored, Duration.ofMillis(100));

        assertTrue(hit.isPresent());
        Rectangle b = hit.get().screenBounds();
        assertEquals(PATCH_X + 75, b.x, 2, "offset halved into capture space");
        assertEquals(PATCH_Y + 12, b.y, 2);
    }

    // ---------------------------------------------------------------- fixtures

    private static BufferedImage patterned(int w, int h, int seed) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, (noise(x, y, seed) << 16) | (noise(x, y, seed + 101) << 8)
                        | noise(x, y, seed + 202));
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
        BufferedImage screen = patterned(700, 400, 3);
        for (int y = 0; y < patch.getHeight(); y++) {
            for (int x = 0; x < patch.getWidth(); x++) {
                screen.setRGB(ox + x, oy + y, patch.getRGB(x, y));
            }
        }
        return screen;
    }

    private static BufferedImage resize(BufferedImage src, double factor) {
        int w = (int) Math.round(src.getWidth() * factor);
        int h = (int) Math.round(src.getHeight() * factor);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
