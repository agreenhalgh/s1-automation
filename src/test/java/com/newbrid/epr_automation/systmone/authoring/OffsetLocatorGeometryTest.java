package com.newbrid.epr_automation.systmone.authoring;

import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.OffsetLocatorCommand;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.OffsetLocatorView;
import com.newbrid.epr_automation.systmone.automation.screen.ImageLocatorResolver;
import com.newbrid.epr_automation.systmone.automation.screen.TemplateStore;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.Map;

import static com.newbrid.epr_automation.systmone.authoring.TestImages.patterned;
import static com.newbrid.epr_automation.systmone.authoring.TestImages.png;
import static com.newbrid.epr_automation.systmone.authoring.TestImages.scaled;
import static com.newbrid.epr_automation.systmone.authoring.TestImages.screenWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one piece of arithmetic in the capture tool that has to be exactly right: an author clicks
 * a point, and the engine must later click <em>that</em> point.
 *
 * <p>Each test derives a locator from a click, then resolves the derived locator with the engine's
 * own {@link ImageLocatorResolver} and asserts the resulting action point is the click. A tool
 * that is a pixel out here produces bots that miss controls in ways no one can trace back.</p>
 */
class OffsetLocatorGeometryTest {

    private static final int ANCHOR_X = 300;
    private static final int ANCHOR_Y = 200;
    private static final BufferedImage ANCHOR = patterned(24, 16, 7);
    private static final BufferedImage SIZING = patterned(60, 18, 11);

    /** Anchor and sizing template as cropped from the worker's own capture: no scaling involved. */
    private static final Map<String, BufferedImage> UNSCALED_TEMPLATES =
            Map.of("grid-header", ANCHOR, "row-strip", SIZING);

    private record Fixture(AuthoringService service, TemplateStore store, BufferedImage screen,
                           String captureId, double uiScale) {}

    private static Fixture fixture(Map<String, BufferedImage> templates, double uiScale) {
        BufferedImage screen = screenWith(ANCHOR, ANCHOR_X, ANCHOR_Y, 800, 600);

        RegistryTemplateStore store = mock(RegistryTemplateStore.class);
        when(store.load(anyString())).thenAnswer(call -> templates.get(call.getArgument(0, String.class)));

        RegistryClient registry = mock(RegistryClient.class);
        when(registry.saveLocator(anyString(), anyString(), any())).thenReturn("locator-1");

        AuthoringProperties props = new AuthoringProperties();
        CaptureStore captures = new CaptureStore(props);
        CaptureStore.Capture capture = new CaptureStore.Capture("cap-1", "worker-1", png(screen), screen,
                screen.getWidth(), screen.getHeight(), uiScale, Instant.now(), null);
        captures.put(capture);

        AuthoringService service = new AuthoringService(mock(CaptureClient.class), captures, registry,
                store, props);
        return new Fixture(service, store, screen, capture.id(), uiScale);
    }

    private static OffsetLocatorCommand command(String sizingRef, int targetX, int targetY) {
        return new OffsetLocatorCommand("row-4-select", "cap-1", "grid-header", sizingRef,
                targetX, targetY, 0.9, "2024");
    }

    /** Where the engine would act on this locator, resolved against the same screen. */
    private static java.awt.Point engineActionPoint(Fixture f, LocatorDefinition definition) {
        ImageLocatorResolver resolver = new ImageLocatorResolver(f.store());
        ImageLocatorResolver.Resolution r = resolver.resolve(definition, f.screen(), 0.9, f.uiScale());
        assertTrue(r.resolved(), () -> "the derived locator must resolve: " + r.diagnostic());
        Rectangle region = r.region();
        return new java.awt.Point(region.x + region.width / 2, region.y + region.height / 2);
    }

    @Test
    void offsetIsMeasuredFromTheAnchorsTopLeftAndActsOnTheClickedPoint() {
        Fixture f = fixture(UNSCALED_TEMPLATES, 1.0);

        OffsetLocatorView view = f.service().saveOffsetLocator(command(null, 420, 260));

        assertEquals(420 - ANCHOR_X, view.definition().offsetX(), "offset from the anchor's left edge");
        assertEquals(260 - ANCHOR_Y, view.definition().offsetY(), "offset from the anchor's top edge");
        assertNull(view.definition().templateRef(), "no sizing template was chosen");
        assertEquals(new java.awt.Point(420, 260), engineActionPoint(f, view.definition()));
    }

    /**
     * With a sizing template the engine acts on the <em>centre</em> of the sized region, so the
     * derived offset must place the centre — not the corner — on the click. Getting this backwards
     * lands every click half a control away.
     */
    @Test
    void aSizingTemplateShiftsTheOffsetSoTheRegionsCentreLandsOnTheClick() {
        Fixture f = fixture(UNSCALED_TEMPLATES, 1.0);

        OffsetLocatorView view = f.service().saveOffsetLocator(command("row-strip", 420, 260));

        assertEquals(420 - ANCHOR_X - SIZING.getWidth() / 2, view.definition().offsetX());
        assertEquals(260 - ANCHOR_Y - SIZING.getHeight() / 2, view.definition().offsetY());
        assertEquals("row-strip", view.definition().templateRef());
        assertEquals(new java.awt.Point(420, 260), engineActionPoint(f, view.definition()));
        assertEquals(SIZING.getWidth(), view.regionWidth());
        assertEquals(SIZING.getHeight(), view.regionHeight());
    }

    /**
     * The reported geometry is what the engine will compute from the stored numbers, so an author
     * who clicks a point sees the point that will actually be acted on.
     */
    @Test
    void theReportedTargetIsTheEnginesTargetNotTheRawClick() {
        Fixture f = fixture(UNSCALED_TEMPLATES, 1.0);

        OffsetLocatorView view = f.service().saveOffsetLocator(command("row-strip", 500, 300));

        assertEquals(500, view.targetX());
        assertEquals(300, view.targetY());
        assertEquals(view.regionX() + view.regionWidth() / 2, view.targetX());
        assertEquals(view.regionY() + view.regionHeight() / 2, view.targetY());
    }

    /**
     * Templates cropped on a 200%-scaled display are twice the size they appear in a Robot
     * capture, so the anchor matches at x0.5. Offsets are stored in the <em>template's</em> pixel
     * space — the space the engine multiplies back up — so they must be doubled on the way in.
     */
    @Test
    void offsetsAreStoredInTemplatePixelSpaceWhenTheAnchorMatchesRescaled() {
        Map<String, BufferedImage> nativeCrops = Map.of(
                "grid-header", scaled(ANCHOR, 2.0),
                "row-strip", scaled(SIZING, 2.0));
        Fixture f = fixture(nativeCrops, 2.0);

        OffsetLocatorView view = f.service().saveOffsetLocator(command(null, 420, 260));

        assertEquals(2 * (420 - ANCHOR_X), view.definition().offsetX(),
                "capture-space offset doubled into the template's pixel space");
        assertEquals(2 * (260 - ANCHOR_Y), view.definition().offsetY());
        assertEquals(new java.awt.Point(420, 260), engineActionPoint(f, view.definition()),
                "and it still resolves back to the clicked point");
    }

    @Test
    void aSizingTemplateIsAlsoScaledIntoCaptureSpace() {
        Map<String, BufferedImage> nativeCrops = Map.of(
                "grid-header", scaled(ANCHOR, 2.0),
                "row-strip", scaled(SIZING, 2.0));
        Fixture f = fixture(nativeCrops, 2.0);

        OffsetLocatorView view = f.service().saveOffsetLocator(command("row-strip", 420, 260));

        assertEquals(SIZING.getWidth(), view.regionWidth(), 1,
                "the region is the sizing template's size in capture pixels");
        assertEquals(new java.awt.Point(420, 260), engineActionPoint(f, view.definition()));
    }

    /** An offset from an anchor that is not on screen is a number with no meaning. */
    @Test
    void refusesToDeriveAnOffsetFromAnAnchorThatIsNotOnTheScreenshot() {
        Fixture f = fixture(Map.of("grid-header", patterned(24, 16, 99)), 1.0);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                f.service().saveOffsetLocator(command(null, 420, 260)));

        assertTrue(ex.getMessage().contains("not on this screenshot"), ex.getMessage());
    }

    @Test
    void refusesALocatorWithNoName() {
        Fixture f = fixture(UNSCALED_TEMPLATES, 1.0);

        assertThrows(IllegalArgumentException.class, () -> f.service().saveOffsetLocator(
                new OffsetLocatorCommand("  ", "cap-1", "grid-header", null, 420, 260, 0.9, "2024")));
    }
}
