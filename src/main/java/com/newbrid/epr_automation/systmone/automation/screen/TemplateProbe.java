package com.newbrid.epr_automation.systmone.automation.screen;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Offline diagnosis for an image locator that will not match.
 *
 * <p>A failed match tells you nothing on its own: a template that is the wrong crop, the
 * wrong scale, or merely a hair under threshold all fail identically. This scores a
 * template against a saved screenshot across a range of scales and prints the numbers,
 * which separates those cases in one run. Point it at a worker failure screenshot from
 * {@code systmone.worker.screenshot-dir} — that image is exactly what the matcher saw.</p>
 *
 * <pre>
 *   java -cp target/classes \
 *     com.newbrid.epr_automation.systmone.automation.screen.TemplateProbe \
 *     worker-screenshots/&lt;run&gt;-step0.png bots/patient-registration/search.png
 * </pre>
 *
 * <p>Reading the output: a peak at scale 1.00 means the template is right and the
 * threshold is the problem. A peak at 0.50 means it was cropped on a display at 200%
 * scaling — Robot captures logical pixels, native screenshot tools crop physical ones.
 * No peak anywhere means the template is not of this screen.</p>
 */
public final class TemplateProbe {

    private static final double MIN_SCALE = 0.25;
    private static final double MAX_SCALE = 1.50;
    private static final double STEP = 0.05;

    private TemplateProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: TemplateProbe <screenshot.png> <template.png>");
            System.exit(2);
        }
        BufferedImage screen = read(args[0]);
        BufferedImage template = read(args[1]);
        TemplateMatcher matcher = new TemplateMatcher();

        System.out.printf("screen   %dx%d   %s%n", screen.getWidth(), screen.getHeight(), args[0]);
        System.out.printf("template %dx%d   %s%n%n", template.getWidth(), template.getHeight(), args[1]);
        System.out.println("scale   size       best   runner-up   at");

        double bestScore = -2;
        double bestScale = 1.0;
        for (double f = MIN_SCALE; f <= MAX_SCALE + 1e-9; f += STEP) {
            BufferedImage candidate = resize(template, f);
            if (candidate == null) continue;
            MatchResult r = matcher.find(screen, candidate);
            if (r == null) continue;
            System.out.printf("%.2f    %4dx%-4d  %6.3f    %6.3f    (%d,%d)%n",
                    f, candidate.getWidth(), candidate.getHeight(),
                    r.score(), r.secondBestScore(), r.x(), r.y());
            if (r.score() > bestScore) {
                bestScore = r.score();
                bestScale = f;
            }
        }

        System.out.printf("%nbest scale %.2f scoring %.3f%n", bestScale, bestScore);
        System.out.println(verdict(bestScale, bestScore));
    }

    private static String verdict(double scale, double score) {
        if (score < 0.5) {
            return "-> No scale matches. This template is not of this screen, or the control "
                    + "has been restyled. Re-crop it from this screenshot.";
        }
        if (Math.abs(scale - 1.0) < 0.03) {
            return "-> Right scale. Raise or lower the locator threshold to sit just under "
                    + String.format("%.3f", score) + ".";
        }
        return String.format("-> Wrong scale: the template is %.2fx the size it appears in this "
                + "capture. It was cropped on a display with different scaling. The worker "
                + "corrects this automatically, but re-cropping from a failure screenshot is "
                + "sharper — resampling costs a little accuracy.", 1.0 / scale);
    }

    private static BufferedImage read(String path) throws Exception {
        File f = new File(path);
        if (!f.canRead()) throw new IllegalArgumentException("cannot read " + f.getAbsolutePath());
        BufferedImage img = ImageIO.read(f);
        if (img == null) throw new IllegalArgumentException("not a readable image: " + path);
        return img;
    }

    private static BufferedImage resize(BufferedImage src, double factor) {
        int w = (int) Math.round(src.getWidth() * factor);
        int h = (int) Math.round(src.getHeight() * factor);
        if (w < 1 || h < 1) return null;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
