package com.newbrid.epr_automation.systmone.authoring;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * Synthetic screens for the authoring tests: noise patches are locally unique, so NCC scores
 * ~1.0 at the one place a patch was blitted in and near zero elsewhere. No display required.
 */
final class TestImages {

    private TestImages() {}

    static BufferedImage patterned(int w, int h, int seed) {
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

    /** A screen of noise with {@code patch} blitted in at (ox, oy). */
    static BufferedImage screenWith(BufferedImage patch, int ox, int oy, int width, int height) {
        BufferedImage screen = patterned(width, height, 3);
        for (int y = 0; y < patch.getHeight(); y++) {
            for (int x = 0; x < patch.getWidth(); x++) {
                screen.setRGB(ox + x, oy + y, patch.getRGB(x, y));
            }
        }
        return screen;
    }

    /** A template as a native screenshot tool would crop it on a display scaled by {@code factor}. */
    static BufferedImage scaled(BufferedImage src, double factor) {
        int w = (int) Math.round(src.getWidth() * factor);
        int h = (int) Math.round(src.getHeight() * factor);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
