package com.newbrid.epr_automation.systmone.automation.screen;

import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.model.ActionType;
import com.newbrid.epr_automation.systmone.common.model.LocatorType;
import com.newbrid.epr_automation.systmone.sdk.locator.ActionParams;
import com.newbrid.epr_automation.systmone.sdk.locator.AutomationBackend;
import com.newbrid.epr_automation.systmone.sdk.locator.ElementHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Screen-recognition automation backend (LocatorType.IMAGE) — the fallback strategy
 * (§2.2, §3.2). Locates saved template images on screen by NCC and drives the mouse /
 * keyboard through {@link RobotInput}.
 *
 * <p>Enforces the fail-safe contract: a match below the locator's confidence threshold,
 * or an ambiguous one, resolves to empty rather than clicking a guessed target
 * (NFR-REL-04, FR-SR-03).</p>
 *
 * <p>Matching is tolerant of desktop scaling. Robot captures the <em>logical</em>
 * desktop while every native screenshot tool crops <em>physical</em> pixels, so on a
 * scaled display a hand-cropped template is larger than the same control in the capture
 * by exactly the scale factor — and NCC has no scale invariance. Templates are therefore
 * also tried shrunk into the capture's pixel space, which is what lets one registry serve
 * a pool of workstations with different scaling (FR-REG-04, FR-WRK-01).</p>
 */
public class ScreenBackend implements AutomationBackend {

    private static final Logger log = LoggerFactory.getLogger(ScreenBackend.class);
    private static final double DEFAULT_THRESHOLD = 0.90;
    private static final double DEFAULT_AMBIGUITY_MARGIN = 0.03;
    private static final long POLL_MS = 200;

    private final TemplateStore store;
    private final RobotInput input;
    private final TemplateMatcher matcher = new TemplateMatcher();
    private final Supplier<BufferedImage> screenSource;
    private final double ambiguityMargin;

    public ScreenBackend(TemplateStore store, RobotInput input) {
        this(store, input, input::captureScreen, DEFAULT_AMBIGUITY_MARGIN);
    }

    /** Testable constructor: inject the screenshot source so no display is required. */
    public ScreenBackend(TemplateStore store, RobotInput input,
                         Supplier<BufferedImage> screenSource, double ambiguityMargin) {
        this.store = store;
        this.input = input;
        this.screenSource = screenSource;
        this.ambiguityMargin = ambiguityMargin;
    }

    @Override
    public LocatorType handles() {
        return LocatorType.IMAGE;
    }

    @Override
    public boolean isAvailable() {
        return input != null && input.isAvailable();
    }

    @Override
    public Optional<ElementHandle> resolve(LocatorDefinition locator, Duration timeout) {
        double threshold = locator.threshold() != null ? locator.threshold() : DEFAULT_THRESHOLD;
        long deadline = System.currentTimeMillis() + Math.max(0, timeout.toMillis());
        Miss miss = null;
        do {
            BufferedImage screen = screenSource.get();
            if (screen != null) {
                Attempt attempt = matchOnce(locator, screen, threshold);
                if (attempt.hit() != null) return Optional.of(attempt.hit());
                miss = attempt.miss();
            }
            if (System.currentTimeMillis() >= deadline) break;
            sleep(Math.min(POLL_MS, Math.max(0, deadline - System.currentTimeMillis())));
        } while (System.currentTimeMillis() < deadline);
        // Reported once, on giving up, rather than on every poll. Without the actual
        // score a miss is indistinguishable from a wrong template, a wrong threshold and
        // a wrong scale — the difference between a one-minute and a one-day diagnosis.
        if (miss != null) log.warn("{}", miss.describe(threshold));
        return Optional.empty();
    }

    /** One pass over a fresh capture: either a confident hit, or why it was not one. */
    private record Attempt(ElementHandle hit, Miss miss) {
        static Attempt of(ElementHandle hit) {
            return new Attempt(hit, null);
        }

        static Attempt of(Miss miss) {
            return new Attempt(null, miss);
        }
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

    private Attempt matchOnce(LocatorDefinition locator, BufferedImage screen, double threshold) {
        // Anchor-relative locating (FR-SR-07): find a stable anchor, act at a fixed offset.
        if (locator.anchorTemplateRef() != null) {
            BufferedImage anchor = require(locator.anchorTemplateRef(), "anchorTemplateRef");
            Scored a = bestAcrossScales(screen, anchor, threshold);
            boolean ambiguous = a.result() != null && a.result().isAmbiguous(ambiguityMargin);
            if (a.result() == null || a.result().score() < threshold || ambiguous) {
                return Attempt.of(miss(locator.anchorTemplateRef(), a, anchor, screen, ambiguous));
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
            Rectangle rect = new Rectangle(a.result().x() + ox, a.result().y() + oy, w, h);
            return Attempt.of(new ImageElementHandle(rect, a.result().score(), a.factor()));
        }

        BufferedImage template = require(locator.templateRef(), "templateRef");
        Scored m = bestAcrossScales(screen, template, threshold);
        if (m.result() == null || m.result().score() < threshold) {
            return Attempt.of(miss(locator.templateRef(), m, template, screen, false));
        }
        if (m.result().isAmbiguous(ambiguityMargin)) {
            return Attempt.of(miss(locator.templateRef(), m, template, screen, true));
        }
        return Attempt.of(new ImageElementHandle(m.result().rectangle(), m.result().score(), m.factor()));
    }

    /** A match, plus the factor the template was scaled by to achieve it. */
    private record Scored(MatchResult result, double factor) {}

    /**
     * Try the template as stored, then — on a scaled desktop — shrunk into the capture's
     * pixel space, stopping at the first confident hit and otherwise keeping the best
     * score seen so the failure can say how close it got.
     */
    private Scored bestAcrossScales(BufferedImage screen, BufferedImage template, double threshold) {
        Scored best = new Scored(null, 1.0);
        for (double factor : scaleCandidates()) {
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
    private double[] scaleCandidates() {
        double scale = input == null ? 1.0 : input.uiScale();
        if (scale <= 1.0 + 1e-6) return new double[]{1.0};
        return new double[]{1.0, 1.0 / scale};
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

    private static int scaledOffset(Integer offset, double factor) {
        return offset == null ? 0 : (int) Math.round(offset * factor);
    }

    private Miss miss(String ref, Scored scored, BufferedImage template, BufferedImage screen,
                      boolean ambiguous) {
        return new Miss(ref, scored.result(), scored.factor(), ambiguous,
                template.getWidth(), template.getHeight(),
                screen.getWidth(), screen.getHeight(),
                input == null ? 1.0 : input.uiScale());
    }

    private BufferedImage require(String ref, String field) {
        BufferedImage img = store.load(ref);
        if (img == null) {
            throw new IllegalStateException(field + " '" + ref
                    + "' did not resolve to a template image (not in the registry, or not a readable image)");
        }
        return img;
    }

    @Override
    public String perform(ActionType action, ElementHandle element, ActionParams params) {
        Rectangle b = element.screenBounds();
        int centreX = b.x + b.width / 2;
        int centreY = b.y + b.height / 2;
        // Click point: the element's centre shifted by the step's offset, if any. The
        // offset is measured on the author's screenshot, so it scales with the template
        // for the same reason the locator's own offsets do.
        double factor = element instanceof ImageElementHandle h ? h.matchScale() : 1.0;
        int cx = centreX + scaledOffset(params.offsetX(), factor);
        int cy = centreY + scaledOffset(params.offsetY(), factor);
        switch (action) {
            case CLICK, FOCUS, SELECT -> input.click(cx, cy);
            case DOUBLE_CLICK -> input.doubleClick(cx, cy);
            case RIGHT_CLICK -> input.rightClick(cx, cy);
            case HOVER -> input.hover(cx, cy);
            // For DRAG the offset is the drag delta, so it applies once — to the end point
            // only, from the element's centre (same semantics as the JAB backend).
            case DRAG -> input.drag(new Point(centreX, centreY), new Point(cx, cy));
            case SET_TEXT -> {
                input.click(cx, cy);
                input.keys("CTRL+A");
                input.keys("DELETE");
                input.type(params.text() == null ? "" : params.text());
            }
            case KEYS -> input.keys(params.keys() != null ? params.keys() : params.text());
            case WAIT -> { /* resolution alone satisfies WAIT */ }
            case ACTIVATE -> throw new UnsupportedOperationException(
                    "ACTIVATE acts on a window, not an element — the engine performs it "
                            + "through WindowControl, so no backend implements it");
            case GET_TEXT, READ_STATE, EXPAND, COLLAPSE ->
                    throw new UnsupportedOperationException(
                            "Screen-recognition backend cannot perform " + action + " (use a JAB locator)");
        }
        return null;
    }

    @Override
    public BufferedImage captureScreenshot() {
        return screenSource.get();
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
