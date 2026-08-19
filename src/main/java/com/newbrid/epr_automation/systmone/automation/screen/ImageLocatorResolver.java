package com.newbrid.epr_automation.systmone.automation.screen;

import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Resolves an IMAGE locator against <em>one</em> supplied screenshot, and says why when it
 * cannot. This is the matching semantics itself — threshold, ambiguity, DPI rescaling and
 * anchor-relative offsets (FR-SR-02/03/07) — with no notion of time or input devices.
 *
 * <p>Two callers share it deliberately: {@link ScreenBackend}, which polls fresh captures
 * until a deadline and then acts, and the authoring tool, which tests a locator against a
 * captured screenshot and shows the author the matched region, the score and the diagnosis.
 * An authoring tool that judged matches by rules of its own would be worse than no tool — it
 * would certify locators the engine then fails to resolve.</p>
 */
public class ImageLocatorResolver {

    public static final double DEFAULT_THRESHOLD = 0.90;
    public static final double DEFAULT_AMBIGUITY_MARGIN = 0.03;

    private static final Logger log = LoggerFactory.getLogger(ImageLocatorResolver.class);

    private final TemplateStore store;
    private final TemplateMatcher matcher = new TemplateMatcher();
    private final double ambiguityMargin;

    public ImageLocatorResolver(TemplateStore store) {
        this(store, DEFAULT_AMBIGUITY_MARGIN);
    }

    public ImageLocatorResolver(TemplateStore store, double ambiguityMargin) {
        this.store = store;
        this.ambiguityMargin = ambiguityMargin;
    }

    /**
     * The outcome of one pass over one screenshot.
     *
     * @param region      the region to act on, or {@code null} if the locator did not resolve.
     *                    For an anchored locator this is the offset target, not the anchor.
     * @param matchRegion where the matched template was found — the anchor's region for an
     *                    anchored locator, otherwise the same as {@code region}. Null only
     *                    when the template was too large for the capture to be tried at all.
     * @param matchedRef  the template reference that was matched (the anchor's, when anchored)
     * @param match       the raw best match for that reference, including the runner-up score
     * @param factor      the scale the template was matched at: 1.0, or 1/uiScale when the
     *                    template was cropped on a differently-scaled desktop
     * @param ambiguous   a second, distinct location scored within the ambiguity margin
     * @param diagnostic  why it did not resolve; {@code null} when it did
     */
    public record Resolution(Rectangle region, Rectangle matchRegion, String matchedRef,
                             MatchResult match, double factor, boolean ambiguous, String diagnostic) {

        public boolean resolved() {
            return region != null;
        }

        /** Best score achieved for {@link #matchedRef}, or 0 when the template could not be tried. */
        public double score() {
            return match == null ? 0 : match.score();
        }
    }

    /**
     * @param screen  the capture to search — for faithful results, one taken by the machine
     *                and at the resolution the bot will run on
     * @param uiScale desktop scale factor of the machine that produced {@code screen}
     * @throws IllegalStateException if a referenced template is not in the store or is not a
     *                               readable image: a locator that cannot load its template can
     *                               never resolve, so this fails rather than reporting a miss
     */
    public Resolution resolve(LocatorDefinition locator, BufferedImage screen, double threshold, double uiScale) {
        // Anchor-relative locating (FR-SR-07): find a stable anchor, act at a fixed offset.
        if (locator.anchorTemplateRef() != null) {
            BufferedImage anchor = require(locator.anchorTemplateRef(), "anchorTemplateRef");
            Scored a = bestAcrossScales(screen, anchor, threshold, uiScale);
            boolean ambiguous = a.result() != null && a.result().isAmbiguous(ambiguityMargin);
            if (a.result() == null || a.result().score() < threshold || ambiguous) {
                return miss(locator.anchorTemplateRef(), a, anchor, screen, ambiguous, threshold, uiScale);
            }
            // Offsets are measured on the same screenshot the template was cropped from,
            // so they live in the template's pixel space and scale along with it.
            int ox = scaledOffset(locator.offsetX(), a.factor());
            int oy = scaledOffset(locator.offsetY(), a.factor());
            int w = 1;
            int h = 1;
            if (locator.templateRef() != null) {
                // Sizing template: an unresolved one used to leave a 1x1 region, which clicks
                // at the raw offset instead of the control's centre. Fail the step instead.
                BufferedImage t = require(locator.templateRef(), "templateRef");
                w = Math.max(1, (int) Math.round(t.getWidth() * a.factor()));
                h = Math.max(1, (int) Math.round(t.getHeight() * a.factor()));
            }
            Rectangle target = new Rectangle(a.result().x() + ox, a.result().y() + oy, w, h);
            return new Resolution(target, a.result().rectangle(), locator.anchorTemplateRef(),
                    a.result(), a.factor(), false, null);
        }

        BufferedImage template = require(locator.templateRef(), "templateRef");
        Scored m = bestAcrossScales(screen, template, threshold, uiScale);
        if (m.result() == null || m.result().score() < threshold) {
            return miss(locator.templateRef(), m, template, screen, false, threshold, uiScale);
        }
        if (m.result().isAmbiguous(ambiguityMargin)) {
            return miss(locator.templateRef(), m, template, screen, true, threshold, uiScale);
        }
        return new Resolution(m.result().rectangle(), m.result().rectangle(), locator.templateRef(),
                m.result(), m.factor(), false, null);
    }

    /**
     * Locate one template on a screenshot regardless of threshold — what the authoring tool
     * overlays on a capture to show where each registered template is found and how well. The
     * result is always "resolved" if the template fits the capture; read {@link
     * Resolution#score()} and {@link Resolution#ambiguous()} to judge it.
     */
    public Resolution locate(String templateRef, BufferedImage screen, double uiScale) {
        BufferedImage template = require(templateRef, "templateRef");
        // A threshold no NCC score can reach forces every scale candidate to be tried, so the
        // overlay shows the best available match rather than the first adequate one.
        Scored m = bestAcrossScales(screen, template, 2.0, uiScale);
        if (m.result() == null) {
            return miss(templateRef, m, template, screen, false, DEFAULT_THRESHOLD, uiScale);
        }
        return new Resolution(m.result().rectangle(), m.result().rectangle(), templateRef,
                m.result(), m.factor(), m.result().isAmbiguous(ambiguityMargin), null);
    }

    /** A match, plus the factor the template was scaled by to achieve it. */
    private record Scored(MatchResult result, double factor) {}

    /**
     * Try the template as stored, then — on a scaled desktop — shrunk into the capture's
     * pixel space, stopping at the first confident hit and otherwise keeping the best
     * score seen so the failure can say how close it got.
     */
    private Scored bestAcrossScales(BufferedImage screen, BufferedImage template, double threshold,
                                    double uiScale) {
        Scored best = new Scored(null, 1.0);
        for (double factor : scaleCandidates(uiScale)) {
            BufferedImage candidate = factor == 1.0 ? template : resize(template, factor);
            if (candidate == null) continue;
            MatchResult r = matcher.find(screen, candidate);
            if (r == null) continue;
            if (best.result() == null || r.score() > best.result().score()) {
                best = new Scored(r, factor);
            }
            if (r.score() >= threshold && !r.isAmbiguous(ambiguityMargin)) {
                if (factor != 1.0) {
                    log.info("Template matched at x{} — it was cropped at a different desktop "
                            + "scale than this worker captures", String.format("%.2f", factor));
                }
                break;
            }
        }
        return best;
    }

    /** 1:1 first — the common case and the historical behaviour — then DPI-corrected. */
    private static double[] scaleCandidates(double uiScale) {
        if (uiScale <= 1.0 + 1e-6) return new double[]{1.0};
        return new double[]{1.0, 1.0 / uiScale};
    }

    static BufferedImage resize(BufferedImage src, double factor) {
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

    /**
     * An authored offset converted into capture pixels. Public because it <em>is</em> the offset
     * contract: the authoring tool derives offsets by inverting this, and any tool that rounded
     * differently would place clicks a pixel off the ones the engine makes.
     */
    public static int scaledOffset(Integer offset, double factor) {
        return offset == null ? 0 : (int) Math.round(offset * factor);
    }

    private BufferedImage require(String ref, String field) {
        BufferedImage img = store.load(ref);
        if (img == null) {
            throw new IllegalStateException(field + " '" + ref
                    + "' did not resolve to a template image (not in the registry, or not a readable image)");
        }
        return img;
    }

    private Resolution miss(String ref, Scored scored, BufferedImage template, BufferedImage screen,
                            boolean ambiguous, double threshold, double uiScale) {
        String diagnostic = new Miss(ref, scored.result(), scored.factor(), ambiguous,
                template.getWidth(), template.getHeight(),
                screen.getWidth(), screen.getHeight(), uiScale).describe(threshold);
        Rectangle matchRegion = scored.result() == null ? null : scored.result().rectangle();
        return new Resolution(null, matchRegion, ref, scored.result(), scored.factor(),
                ambiguous, diagnostic);
    }

    /** Everything an author needs to tell under-threshold from ambiguous from wrong-scale. */
    private record Miss(String ref, MatchResult result, double factor, boolean ambiguous,
                        int templateW, int templateH, int screenW, int screenH, double uiScale) {

        String describe(double threshold) {
            if (result == null) {
                return "Template " + quoted(ref) + " (" + templateW + "x" + templateH
                        + ") does not fit the " + screenW + "x" + screenH
                        + " capture, so it can never match";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(ambiguous ? "Ambiguous match for " : "No confident match for ")
                    .append(quoted(ref))
                    .append(": best=").append(fmt(result.score()));
            if (!ambiguous) sb.append(" < threshold=").append(threshold);
            sb.append(", runner-up=").append(fmt(result.secondBestScore()))
                    .append(" at (").append(result.x()).append(",").append(result.y()).append(")")
                    .append("; template ").append(templateW).append("x").append(templateH)
                    .append(" tried at x").append(fmt(factor))
                    .append(" against a ").append(screenW).append("x").append(screenH)
                    .append(" capture");

            if (ambiguous) {
                sb.append(". Two places on screen look alike, so acting would be a guess — ")
                        .append("crop a template with more distinguishing context, or anchor it");
            } else if (result.score() < 0.5) {
                sb.append(". A score this low means the template is not of this screen at all, ")
                        .append("or is at the wrong scale");
                if (uiScale > 1.0 + 1e-6) {
                    sb.append(" (this desktop is scaled x").append(fmt(uiScale))
                            .append(", so a natively cropped template is that much too large; ")
                            .append("re-crop it from a worker failure screenshot)");
                }
            } else {
                sb.append(". Close — re-crop the template, or lower the threshold");
            }
            return sb.toString();
        }

        private static String quoted(String s) {
            return "'" + s + "'";
        }

        private static String fmt(double d) {
            return String.format("%.3f", d);
        }
    }
}
