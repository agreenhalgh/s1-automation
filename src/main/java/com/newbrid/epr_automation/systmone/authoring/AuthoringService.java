package com.newbrid.epr_automation.systmone.authoring;

import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.CaptureView;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.CropView;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.InspectView;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.JabLocatorCommand;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.SavedLocatorView;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.LocateResult;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.OffsetLocatorCommand;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.OffsetLocatorView;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.RegionView;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.TemplateFromSelection;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.TestCommand;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.TestResult;
import com.newbrid.epr_automation.systmone.automation.screen.ImageLocatorResolver;
import com.newbrid.epr_automation.systmone.common.dto.CaptureResponse;
import com.newbrid.epr_automation.systmone.common.dto.InspectResponse;
import com.newbrid.epr_automation.systmone.common.dto.JabNode;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.model.LocatorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * The capture tool's work: pull a screenshot from a worker, cut templates out of it, derive
 * anchor-relative locators from where the author points, and test the result against a fresh
 * screen (FR-TOOL-02/04, FR-SR-07/08).
 *
 * <p>Two rules keep the tool honest. Matching goes through {@link ImageLocatorResolver} — the
 * engine's own resolver — so a locator this tool shows as resolving is one the engine resolves.
 * And offsets are derived from where the anchor was <em>actually matched</em> on the capture,
 * then converted into the template's pixel space, which is the space the engine multiplies back
 * up at run time. Deriving them from the author's raw click instead would work only on displays
 * scaled exactly like the authoring machine.</p>
 */
@Service
@Profile("authoring")
public class AuthoringService {

    private static final Logger log = LoggerFactory.getLogger(AuthoringService.class);

    private final CaptureClient captures;
    private final CaptureStore store;
    private final RegistryClient registry;
    private final RegistryTemplateStore templates;
    private final ImageLocatorResolver resolver;
    private final AuthoringProperties props;

    public AuthoringService(CaptureClient captures, CaptureStore store, RegistryClient registry,
                            RegistryTemplateStore templates, AuthoringProperties props) {
        this.captures = captures;
        this.store = store;
        this.registry = registry;
        this.templates = templates;
        this.props = props;
        this.resolver = new ImageLocatorResolver(templates);
    }

    // ---- capture ----

    /**
     * @throws IllegalStateException if the worker could not produce a screenshot; the message is
     *         the worker's own explanation, which is what the author needs to act on
     */
    public CaptureView capture(String workerId, boolean activateWindow) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("Choose a worker to capture from");
        }
        CaptureResponse response = captures.capture(workerId, activateWindow);
        if (!response.ok()) {
            throw new IllegalStateException(response.error() == null
                    ? "Worker '" + workerId + "' returned no image" : response.error());
        }
        byte[] png = Base64.getDecoder().decode(response.imageBase64());
        BufferedImage image = read(png);
        CaptureStore.Capture capture = new CaptureStore.Capture(UUID.randomUUID().toString(),
                response.workerId(), png, image, image.getWidth(), image.getHeight(),
                response.uiScale(), Instant.now(), null);
        store.put(capture);
        log.info("Held capture {} from worker {} ({}x{} at {}x scaling)", capture.id(),
                capture.workerId(), capture.width(), capture.height(), capture.uiScale());
        return view(capture);
    }

    public List<CaptureView> heldCaptures() {
        return store.list().stream().map(AuthoringService::view).toList();
    }

    public CaptureView held(String captureId) {
        return view(store.require(captureId));
    }

    public byte[] imageBytes(String captureId) {
        return store.require(captureId).png();
    }

    public boolean discard(String captureId) {
        return store.discard(captureId);
    }

    public int discardAll() {
        return store.discardAll();
    }

    // ---- templates ----

    /**
     * Save the selected region of a capture as a registry template (FR-TOOL-02). The crop comes
     * out of the worker's own pixels, which is what makes the template match at run time without
     * any scale correction at all.
     */
    public LocateResult saveTemplate(TemplateFromSelection cmd) {
        CaptureStore.Capture capture = store.require(cmd.captureId());
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new IllegalArgumentException("Give the template a name — bots reference it by name");
        }
        Rectangle rect = clampToCapture(new Rectangle(cmd.x(), cmd.y(), cmd.width(), cmd.height()), capture);
        BufferedImage crop = capture.image().getSubimage(rect.x, rect.y, rect.width, rect.height);
        double threshold = cmd.threshold() != null ? cmd.threshold() : props.getDefaultThreshold();

        registry.uploadTemplate(cmd.name().trim(), s1Version(cmd.s1Version()), threshold,
                cmd.screenContext(), Base64.getEncoder().encodeToString(png(crop)));
        // The registry now holds different bytes under this name than any cached copy.
        templates.invalidate(cmd.name().trim());
        store.addCrop(capture.id(), cmd.name().trim(), rect);
        log.info("Saved template '{}' ({}x{} at {},{}) from capture {}", cmd.name(), rect.width,
                rect.height, rect.x, rect.y, capture.id());

        // A template is trivially "found" where it was just cut from, at a perfect score. Report
        // it that way so the page can draw it alongside matched ones without a special case.
        return new LocateResult(cmd.name().trim(), true, rect.x, rect.y, rect.width, rect.height,
                1.0, 0.0, 1.0, false, null);
    }

    /** Where each named template is found on this capture, for the overlay (FR-SR-08). */
    public List<LocateResult> locate(String captureId, List<String> templateRefs) {
        CaptureStore.Capture capture = store.require(captureId);
        if (templateRefs == null || templateRefs.isEmpty()) return List.of();
        // Independent searches over one immutable image: worth parallelising, since a full-screen
        // NCC scan per template is the slowest thing this tool does.
        return templateRefs.parallelStream().map(ref -> locateOne(capture, ref)).toList();
    }

    private LocateResult locateOne(CaptureStore.Capture capture, String ref) {
        try {
            ImageLocatorResolver.Resolution r = resolver.locate(ref, capture.image(), capture.uiScale());
            if (!r.resolved()) {
                return notFound(ref, r.diagnostic());
            }
            Rectangle box = r.region();
            return new LocateResult(ref, true, box.x, box.y, box.width, box.height,
                    r.score(), r.match().secondBestScore(), r.factor(), r.ambiguous(), null);
        } catch (IllegalStateException e) {
            return notFound(ref, e.getMessage());
        }
    }

    // ---- inspect ----

    /**
     * Ask the worker what accessible element sits under a point on this capture, and turn the
     * answer into a locator the author can paste into a bot (FR-TOOL-03, FR-JAB-02).
     *
     * <p>The point is in the capture's pixel space, which is the worker's logical screen space,
     * so it needs no conversion — but it does need checking: a click outside the captured screen
     * would ask the worker about a coordinate that never existed.</p>
     *
     * <p>The reply describes the tree as it is <em>now</em>. If the element comes back outside
     * the bounds of the screenshot the author is looking at, the screen has changed since the
     * capture, and the result is flagged rather than drawn as though it lined up.</p>
     *
     * @throws IllegalStateException if the worker could not inspect; the message is the worker's
     *         own explanation, which is what the author needs to act on
     */
    public InspectView inspect(String captureId, int x, int y) {
        CaptureStore.Capture capture = store.require(captureId);
        if (x < 0 || y < 0 || x >= capture.width() || y >= capture.height()) {
            throw new IllegalArgumentException(String.format(
                    "(%d,%d) is outside this %dx%d capture", x, y, capture.width(), capture.height()));
        }
        return view(capture, captures.inspect(capture.workerId(), x, y));
    }

    /**
     * Ask the worker what SystmOne currently considers focused (FR-TOOL-03).
     *
     * <p>No coordinates involved, which is the point: the author tabs to a field in SystmOne,
     * switches to the tool, and asks. The browser holding the operating system's focus does not
     * disturb what SystmOne's own tree calls focused.</p>
     */
    public InspectView inspectFocused(String captureId) {
        CaptureStore.Capture capture = store.require(captureId);
        return view(capture, captures.inspectFocused(capture.workerId()));
    }

    /** Store a JAB locator in the registry under a name a bot can reference (FR-REG-01). */
    public SavedLocatorView saveJabLocator(JabLocatorCommand cmd) {
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new IllegalArgumentException("Give the locator a name — bots reference it by name");
        }
        LocatorDefinition locator = cmd.locator();
        if (locator == null || locator.type() != LocatorType.JAB) {
            throw new IllegalArgumentException("That is not a JAB locator; inspect an element first");
        }
        if (locator.role() == null && locator.name() == null && locator.description() == null) {
            throw new IllegalStateException("This locator names no role, name or description, so it "
                    + "would match on nothing. Inspect a control that exposes at least one of them, "
                    + "or cut a template instead.");
        }
        String id = registry.saveLocator(cmd.name().trim(), s1Version(cmd.s1Version()), locator);
        log.info("Saved JAB locator '{}' as {}", cmd.name().trim(), id);
        return new SavedLocatorView(id, cmd.name().trim(), locator);
    }

    private InspectView view(CaptureStore.Capture capture, InspectResponse response) {
        if (!response.ok()) {
            throw new IllegalStateException(response.error() == null
                    ? "Worker '" + capture.workerId() + "' returned no element" : response.error());
        }
        JabNode element = response.element();
        boolean stale = element.width() > 0 && element.height() > 0
                && !new Rectangle(0, 0, capture.width(), capture.height())
                        .intersects(new Rectangle(element.x(), element.y(), element.width(), element.height()));
        log.info("Inspected {} '{}' on capture {}: the suggested locator matches {} element(s)",
                element.role(), element.name(), capture.id(), response.matchCount());
        return new InspectView(element, response.ancestors(), response.previousSibling(),
                response.followingSibling(), response.suggested(), response.matchCount(), stale);
    }

    // ---- offset locators ----

    /**
     * Derive an anchor-relative locator from an anchor template and the point the author clicked,
     * and store it (FR-SR-07, FR-REG-01).
     *
     * <p>The anchor is re-located here rather than trusted from the page: the offset is only
     * meaningful relative to where the matcher actually finds the anchor, and that is the number
     * the engine will start from.</p>
     */
    public OffsetLocatorView saveOffsetLocator(OffsetLocatorCommand cmd) {
        CaptureStore.Capture capture = store.require(cmd.captureId());
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new IllegalArgumentException("Give the locator a name");
        }
        if (cmd.anchorTemplateRef() == null || cmd.anchorTemplateRef().isBlank()) {
            throw new IllegalArgumentException("Choose an anchor template for the offset to be measured from");
        }
        double threshold = cmd.threshold() != null ? cmd.threshold() : props.getDefaultThreshold();
        LocateResult anchor = locateOne(capture, cmd.anchorTemplateRef());
        requireUsableAnchor(cmd.anchorTemplateRef(), anchor, threshold);

        double factor = anchor.factor();
        Rectangle region = new Rectangle(anchor.x(), anchor.y(), 1, 1);
        if (cmd.sizingTemplateRef() != null && !cmd.sizingTemplateRef().isBlank()) {
            BufferedImage sizing = templates.load(cmd.sizingTemplateRef());
            if (sizing == null) {
                throw new IllegalStateException("Sizing template '" + cmd.sizingTemplateRef()
                        + "' is not in the registry");
            }
            region.width = Math.max(1, (int) Math.round(sizing.getWidth() * factor));
            region.height = Math.max(1, (int) Math.round(sizing.getHeight() * factor));
        }

        // The engine acts on the centre of the resolved region, so the offset must place the
        // centre — not the corner — on the author's point. Then convert capture pixels into the
        // template's own pixel space, which is what the engine scales back up (see
        // ImageLocatorResolver: offsets are multiplied by the match factor).
        int dx = cmd.targetX() - anchor.x() - region.width / 2;
        int dy = cmd.targetY() - anchor.y() - region.height / 2;
        int offsetX = (int) Math.round(dx / factor);
        int offsetY = (int) Math.round(dy / factor);

        LocatorDefinition definition = LocatorDefinition.imageAnchored(
                blankToNull(cmd.sizingTemplateRef()), threshold, cmd.anchorTemplateRef().trim(),
                offsetX, offsetY);

        String locatorId = registry.saveLocator(cmd.name().trim(), s1Version(cmd.s1Version()), definition);
        log.info("Saved locator '{}': anchor '{}' offset ({},{}) — target ({},{}) on capture {}",
                cmd.name(), cmd.anchorTemplateRef(), offsetX, offsetY, cmd.targetX(), cmd.targetY(),
                capture.id());

        // Report the geometry the engine will compute from the stored numbers, not the click that
        // produced them: if rounding into template space moved the point, the author sees it.
        Rectangle resolved = engineRegion(anchor, definition, region.width, region.height);
        return new OffsetLocatorView(locatorId, cmd.name().trim(), definition, anchor,
                resolved.x + resolved.width / 2, resolved.y + resolved.height / 2,
                resolved.x, resolved.y, resolved.width, resolved.height);
    }

    /**
     * An offset is only as trustworthy as the anchor it is measured from, so the anchor must clear
     * the same bar the engine will hold it to. Anything else stores a number derived from where the
     * matcher happened to score highest on a screen the anchor is not even on — and the failure
     * would surface later as a click in empty space rather than as a rejected locator here.
     */
    private static void requireUsableAnchor(String ref, LocateResult anchor, double threshold) {
        if (!anchor.found()) {
            throw new IllegalStateException("The anchor template '" + ref
                    + "' is not on this screenshot, so an offset from it would be meaningless. "
                    + (anchor.diagnostic() == null ? "" : anchor.diagnostic()));
        }
        if (anchor.score() < threshold) {
            throw new IllegalStateException(String.format(
                    "The anchor template '%s' is not on this screenshot: the best match anywhere "
                            + "scores %.3f, under the %.2f threshold this locator would use. Capture the "
                            + "screen the anchor is actually on, or re-crop it.", ref, anchor.score(), threshold));
        }
        if (anchor.ambiguous()) {
            throw new IllegalStateException(String.format(
                    "The anchor template '%s' matches two places on this screenshot almost equally "
                            + "(%.3f against a runner-up of %.3f), so an offset from it would land "
                            + "wherever the matcher happened to prefer. Crop an anchor with more "
                            + "distinguishing context.", ref, anchor.score(), anchor.runnerUp()));
        }
    }

    private static Rectangle engineRegion(LocateResult anchor, LocatorDefinition def, int width, int height) {
        int ox = ImageLocatorResolver.scaledOffset(def.offsetX(), anchor.factor());
        int oy = ImageLocatorResolver.scaledOffset(def.offsetY(), anchor.factor());
        return new Rectangle(anchor.x() + ox, anchor.y() + oy, width, height);
    }

    // ---- test ----

    /**
     * Resolve a locator against a screen captured now (FR-TOOL-04): the same call the engine
     * makes, against the same worker, reported with the score, the elapsed time and — when it
     * fails — the reason.
     */
    public TestResult test(TestCommand cmd) {
        LocatorDefinition locator = cmd.locator();
        if (locator == null) {
            throw new IllegalArgumentException("Nothing to test — choose a template or a locator");
        }
        CaptureView fresh = capture(cmd.workerId(), cmd.activateWindow());
        CaptureStore.Capture capture = store.require(fresh.id());
        double threshold = cmd.threshold() != null ? cmd.threshold()
                : locator.threshold() != null ? locator.threshold() : props.getDefaultThreshold();

        long started = System.nanoTime();
        ImageLocatorResolver.Resolution r;
        try {
            r = resolver.resolve(locator, capture.image(), threshold, capture.uiScale());
        } catch (IllegalStateException e) {
            long elapsed = elapsedMs(started);
            return new TestResult(cmd.label(), false, fresh, null, null, null, null, threshold,
                    elapsed, e.getMessage());
        }
        long elapsed = elapsedMs(started);

        LocateResult match = r.matchRegion() == null
                ? notFound(r.matchedRef(), r.diagnostic())
                : new LocateResult(r.matchedRef(), r.resolved(), r.matchRegion().x, r.matchRegion().y,
                        r.matchRegion().width, r.matchRegion().height, r.score(),
                        r.match() == null ? 0 : r.match().secondBestScore(), r.factor(), r.ambiguous(),
                        r.diagnostic());

        if (!r.resolved()) {
            return new TestResult(cmd.label(), false, fresh, match, null, null, null, threshold,
                    elapsed, r.diagnostic());
        }
        Rectangle region = r.region();
        return new TestResult(cmd.label(), true, fresh, match,
                new RegionView(region.x, region.y, region.width, region.height),
                region.x + region.width / 2, region.y + region.height / 2,
                threshold, elapsed, null);
    }

    // ---- helpers ----

    private static LocateResult notFound(String ref, String diagnostic) {
        return new LocateResult(ref, false, 0, 0, 0, 0, 0, 0, 1.0, false, diagnostic);
    }

    private static CaptureView view(CaptureStore.Capture c) {
        List<CropView> crops = c.crops().stream()
                .map(crop -> new CropView(crop.templateName(), crop.rect().x, crop.rect().y,
                        crop.rect().width, crop.rect().height))
                .toList();
        return new CaptureView(c.id(), c.workerId(), c.width(), c.height(), c.uiScale(), c.capturedAt(),
                "/authoring/captures/" + c.id() + ".png", crops);
    }

    private String s1Version(String requested) {
        return requested == null || requested.isBlank() ? props.getS1Version() : requested.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static long elapsedMs(long startedNanos) {
        return Math.round((System.nanoTime() - startedNanos) / 1_000_000.0);
    }

    /**
     * A selection dragged to the edge of the image, or one pixel wide, must not become an
     * unmatched template later — reject what cannot work and trim what merely overhangs.
     */
    private static Rectangle clampToCapture(Rectangle rect, CaptureStore.Capture capture) {
        Rectangle clamped = rect.intersection(new Rectangle(0, 0, capture.width(), capture.height()));
        if (clamped.isEmpty() || clamped.width < 4 || clamped.height < 4) {
            throw new IllegalArgumentException("That selection is " + rect.width + "x" + rect.height
                    + " within the capture — too small to match reliably. Drag a region of at least "
                    + "4x4 pixels over the control.");
        }
        return clamped;
    }

    private static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not encode the selected region as PNG", e);
        }
    }

    private static BufferedImage read(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) throw new IllegalStateException("The worker sent bytes that are not an image");
            return image;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not decode the worker's screenshot", e);
        }
    }
}
