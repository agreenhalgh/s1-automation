package com.newbrid.epr_automation.systmone.automation.screen;

import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.model.ActionType;
import com.newbrid.epr_automation.systmone.common.model.LocatorType;
import com.newbrid.epr_automation.systmone.sdk.locator.ActionParams;
import com.newbrid.epr_automation.systmone.sdk.locator.AutomationBackend;
import com.newbrid.epr_automation.systmone.sdk.locator.ElementHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Screen-recognition automation backend (LocatorType.IMAGE) — the fallback strategy
 * (§2.2, §3.2). Locates saved template images on screen and drives the mouse / keyboard
 * through {@link RobotInput}.
 *
 * <p>The matching itself — threshold, ambiguity, DPI rescaling, anchor offsets — lives in
 * {@link ImageLocatorResolver}, which the authoring tool shares so that a locator it
 * certifies is one this backend resolves. What this class adds is <em>time</em> and
 * <em>action</em>: polling fresh captures until the step's timeout, and driving input at the
 * resolved region.</p>
 *
 * <p>Enforces the fail-safe contract: a match below the locator's confidence threshold,
 * or an ambiguous one, resolves to empty rather than clicking a guessed target
 * (NFR-REL-04, FR-SR-03).</p>
 */
public class ScreenBackend implements AutomationBackend {

    private static final Logger log = LoggerFactory.getLogger(ScreenBackend.class);
    private static final long POLL_MS = 200;

    private final RobotInput input;
    private final ImageLocatorResolver resolver;
    private final Supplier<BufferedImage> screenSource;

    public ScreenBackend(TemplateStore store, RobotInput input) {
        this(store, input, input::captureScreen, ImageLocatorResolver.DEFAULT_AMBIGUITY_MARGIN);
    }

    /** Testable constructor: inject the screenshot source so no display is required. */
    public ScreenBackend(TemplateStore store, RobotInput input,
                         Supplier<BufferedImage> screenSource, double ambiguityMargin) {
        this.input = input;
        this.screenSource = screenSource;
        this.resolver = new ImageLocatorResolver(store, ambiguityMargin);
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
        double threshold = locator.threshold() != null
                ? locator.threshold() : ImageLocatorResolver.DEFAULT_THRESHOLD;
        long deadline = System.currentTimeMillis() + Math.max(0, timeout.toMillis());
        String diagnostic = null;
        do {
            BufferedImage screen = screenSource.get();
            if (screen != null) {
                ImageLocatorResolver.Resolution r = resolver.resolve(locator, screen, threshold, uiScale());
                if (r.resolved()) {
                    return Optional.of(new ImageElementHandle(r.region(), r.score(), r.factor()));
                }
                diagnostic = r.diagnostic();
            }
            if (System.currentTimeMillis() >= deadline) break;
            sleep(Math.min(POLL_MS, Math.max(0, deadline - System.currentTimeMillis())));
        } while (System.currentTimeMillis() < deadline);
        // Reported once, on giving up, rather than on every poll. Without the actual
        // score a miss is indistinguishable from a wrong template, a wrong threshold and
        // a wrong scale — the difference between a one-minute and a one-day diagnosis.
        if (diagnostic != null) log.warn("{}", diagnostic);
        return Optional.empty();
    }

    private double uiScale() {
        return input == null ? 1.0 : input.uiScale();
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
        int cx = centreX + ImageLocatorResolver.scaledOffset(params.offsetX(), factor);
        int cy = centreY + ImageLocatorResolver.scaledOffset(params.offsetY(), factor);
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
