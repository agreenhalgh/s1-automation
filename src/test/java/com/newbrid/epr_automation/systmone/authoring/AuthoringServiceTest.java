package com.newbrid.epr_automation.systmone.authoring;

import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.CaptureView;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.LocateResult;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.TemplateFromSelection;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.TestCommand;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.TestResult;
import com.newbrid.epr_automation.systmone.common.dto.CaptureResponse;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.newbrid.epr_automation.systmone.authoring.TestImages.patterned;
import static com.newbrid.epr_automation.systmone.authoring.TestImages.png;
import static com.newbrid.epr_automation.systmone.authoring.TestImages.screenWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Capture, crop and live-test behaviour of the capture tool (FR-TOOL-02/04). */
class AuthoringServiceTest {

    private static final BufferedImage BUTTON = patterned(30, 20, 5);
    private static final int BUTTON_X = 120;
    private static final int BUTTON_Y = 80;
    private static final BufferedImage SCREEN = screenWith(BUTTON, BUTTON_X, BUTTON_Y, 400, 300);

    private CaptureClient captureClient;
    private RegistryClient registry;
    private RegistryTemplateStore templateStore;
    private CaptureStore captures;
    private AuthoringService service;
    /** Templates the fake registry has been given, so a saved crop can be matched straight back. */
    private final Map<String, BufferedImage> stored = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        captureClient = mock(CaptureClient.class);
        registry = mock(RegistryClient.class);
        templateStore = mock(RegistryTemplateStore.class);
        when(templateStore.load(anyString())).thenAnswer(c -> stored.get(c.getArgument(0, String.class)));
        when(registry.uploadTemplate(anyString(), anyString(), anyDouble(), any(), anyString()))
                .thenAnswer(call -> {
                    String name = call.getArgument(0, String.class);
                    byte[] bytes = Base64.getDecoder().decode(call.getArgument(4, String.class));
                    stored.put(name, ImageIO.read(new ByteArrayInputStream(bytes)));
                    return "template-" + name;
                });

        AuthoringProperties props = new AuthoringProperties();
        captures = new CaptureStore(props);
        service = new AuthoringService(captureClient, captures, registry, templateStore, props);
    }

    private CaptureView capture(BufferedImage screen, double uiScale) {
        when(captureClient.capture(eq("worker-1"), anyBoolean())).thenReturn(new CaptureResponse(
                "req-1", "worker-1", screen.getWidth(), screen.getHeight(), uiScale, "SystmOne",
                Base64.getEncoder().encodeToString(png(screen)), null));
        return service.capture("worker-1", false);
    }

    @Test
    void capturesFromAWorkerAndHoldsTheScreenshotInMemory() {
        CaptureView view = capture(SCREEN, 1.0);

        assertEquals("worker-1", view.workerId());
        assertEquals(400, view.width());
        assertEquals(300, view.height());
        assertTrue(view.imageUrl().endsWith(view.id() + ".png"));
        assertNotNull(service.imageBytes(view.id()));
        assertEquals(1, service.heldCaptures().size());
    }

    @Test
    void reportsTheWorkersOwnReasonWhenItCannotCapture() {
        when(captureClient.capture(anyString(), anyBoolean())).thenReturn(
                CaptureResponse.failed("req-1", "worker-1", "no interactive desktop session"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.capture("worker-1", false));

        assertEquals("no interactive desktop session", ex.getMessage());
    }

    /**
     * The uploaded template must be exactly the selected pixels of the worker's capture — that is
     * the whole reason for capturing through the worker, so it is worth asserting rather than
     * assuming: the crop is matched back against the screen it came from and must score ~1.0 at
     * the place it was cut.
     */
    @Test
    void savesTheSelectedRegionAsATemplateThatMatchesWhereItWasCut() {
        CaptureView view = capture(SCREEN, 1.0);

        LocateResult saved = service.saveTemplate(new TemplateFromSelection(view.id(), "search-button",
                BUTTON_X, BUTTON_Y, 30, 20, 0.9, "patient-record", "2024"));

        assertTrue(saved.found());
        assertEquals(BUTTON_X, saved.x());
        assertEquals(30, saved.width());

        ArgumentCaptor<String> uploaded = ArgumentCaptor.forClass(String.class);
        verify(registry).uploadTemplate(eq("search-button"), eq("2024"), eq(0.9),
                eq("patient-record"), uploaded.capture());
        BufferedImage crop = stored.get("search-button");
        assertEquals(30, crop.getWidth());
        assertEquals(20, crop.getHeight());

        List<LocateResult> located = service.locate(view.id(), List.of("search-button"));
        assertTrue(located.getFirst().found());
        assertEquals(BUTTON_X, located.getFirst().x(), 1);
        assertEquals(BUTTON_Y, located.getFirst().y(), 1);
        assertTrue(located.getFirst().score() > 0.99, "the crop must match its own source pixels");
        verify(templateStore).invalidate("search-button");
    }

    @Test
    void recordsTheCropOnTheCaptureSoTheOverlayCanShowIt() {
        CaptureView view = capture(SCREEN, 1.0);

        service.saveTemplate(new TemplateFromSelection(view.id(), "search-button",
                BUTTON_X, BUTTON_Y, 30, 20, null, null, null));

        CaptureView reloaded = service.held(view.id());
        assertEquals(1, reloaded.crops().size());
        assertEquals("search-button", reloaded.crops().getFirst().templateName());
        assertEquals(BUTTON_X, reloaded.crops().getFirst().x());
    }

    @Test
    void rejectsASelectionTooSmallToMatchReliably() {
        CaptureView view = capture(SCREEN, 1.0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.saveTemplate(new TemplateFromSelection(view.id(), "sliver",
                        10, 10, 2, 2, null, null, null)));

        assertTrue(ex.getMessage().contains("too small"), ex.getMessage());
    }

    @Test
    void rejectsATemplateWithNoName() {
        CaptureView view = capture(SCREEN, 1.0);

        assertThrows(IllegalArgumentException.class, () -> service.saveTemplate(
                new TemplateFromSelection(view.id(), " ", 10, 10, 20, 20, null, null, null)));
    }

    /**
     * Locating is threshold-free by design — the overlay shows the best candidate and its score so
     * the author can see how close it got. Judging it is the caller's job.
     */
    @Test
    void locatingReportsTheBestCandidateAndItsScoreEvenWhenItIsNothingLikeAMatch() {
        CaptureView view = capture(SCREEN, 1.0);
        stored.put("elsewhere", patterned(30, 20, 77));

        LocateResult result = service.locate(view.id(), List.of("elsewhere")).getFirst();

        assertTrue(result.found(), "a template that fits the capture always has a best match");
        assertTrue(result.score() < 0.5, "but it scores nowhere near a real match: " + result.score());
    }

    @Test
    void locatingAnUnregisteredTemplateIsReportedNotThrown() {
        CaptureView view = capture(SCREEN, 1.0);

        LocateResult result = service.locate(view.id(), List.of("never-uploaded")).getFirst();

        assertFalse(result.found());
        assertTrue(result.diagnostic().contains("did not resolve to a template image"), result.diagnostic());
    }

    /** A test takes a new screenshot: the result must describe that capture, not the working one. */
    @Test
    void testingResolvesAgainstAFreshCaptureAndReportsTheActionPoint() {
        CaptureView working = capture(SCREEN, 1.0);
        service.saveTemplate(new TemplateFromSelection(working.id(), "search-button",
                BUTTON_X, BUTTON_Y, 30, 20, 0.9, null, null));

        // The screen has moved on: the same control, 40px lower.
        BufferedImage moved = screenWith(BUTTON, BUTTON_X, BUTTON_Y + 40, 400, 300);
        capture(moved, 1.0);

        TestResult result = service.test(new TestCommand("worker-1", false, "template search-button",
                LocatorDefinition.image("search-button", 0.9), null));

        assertTrue(result.resolved(), result.diagnostic());
        assertEquals("search-button", result.match().templateRef());
        assertTrue(result.match().score() > 0.99);
        assertEquals(BUTTON_Y + 40, result.region().y(), 1, "found at its new position");
        assertEquals(BUTTON_X + 15, result.clickX(), 1, "acts on the centre of the matched region");
        assertEquals(BUTTON_Y + 40 + 10, result.clickY(), 1);
        assertNotEquals(working.id(), result.capture().id(), "the test ran against a new capture");
    }

    @Test
    void aFailedTestCarriesTheDiagnosisAndTheScreenshotItFailedOn() {
        capture(SCREEN, 1.0);
        stored.put("absent-control", patterned(30, 20, 66));

        TestResult result = service.test(new TestCommand("worker-1", false, "template absent-control",
                LocatorDefinition.image("absent-control", 0.9), null));

        assertFalse(result.resolved());
        assertNotNull(result.capture(), "the author must see the screen the test ran against");
        assertNull(result.region());
        assertTrue(result.diagnostic().contains("No confident match"), result.diagnostic());
        assertTrue(result.elapsedMs() >= 0);
    }

    @Test
    void testingAnUnknownTemplateExplainsRatherThanFailing() {
        capture(SCREEN, 1.0);

        TestResult result = service.test(new TestCommand("worker-1", false, "template ghost",
                LocatorDefinition.image("ghost", 0.9), null));

        assertFalse(result.resolved());
        assertTrue(result.diagnostic().contains("did not resolve to a template image"), result.diagnostic());
    }

    @Test
    void discardedCapturesCannotBeWorkedOnAndSayWhy() {
        CaptureView view = capture(SCREEN, 1.0);
        assertTrue(service.discard(view.id()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.held(view.id()));

        assertTrue(ex.getMessage().contains("no longer held"), ex.getMessage());
    }
}
