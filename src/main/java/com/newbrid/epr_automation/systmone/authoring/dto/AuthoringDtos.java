package com.newbrid.epr_automation.systmone.authoring.dto;

import com.newbrid.epr_automation.systmone.common.dto.JabNode;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;

import java.time.Instant;
import java.util.List;

/** Payloads exchanged between the capture tool's page and its own JSON endpoints. */
public final class AuthoringDtos {

    private AuthoringDtos() {}

    /**
     * A worker the tool can capture from, as reported by the control plane (FR-CP-06). Mirrors
     * every field the agent-health endpoint sends, not only the ones shown, so the tool does not
     * depend on how strictly its JSON reader treats properties it was not expecting.
     */
    public record WorkerView(String id, String host, String s1Version, int screenWidth, int screenHeight,
                             String capabilities, String adapters, String status, String currentRunId,
                             Instant registeredAt, Instant lastSeenAt) {}

    /** POST /authoring/api/captures */
    public record CaptureCommand(String workerId, boolean activateWindow) {}

    /**
     * A screenshot held in this session, described for the page. The image itself is fetched
     * from {@code imageUrl}; the bytes never leave memory on the server side.
     *
     * @param crops regions of this capture that have been saved as templates, so the page can
     *              overlay the template definitions on the screenshot it was cropped from
     */
    public record CaptureView(String id, String workerId, int width, int height, double uiScale,
                              Instant capturedAt, String imageUrl, List<CropView> crops) {}

    /** A template definition drawn in this capture's own pixel space. */
    public record CropView(String templateName, int x, int y, int width, int height) {}

    /** POST /authoring/api/templates — save a selected region of a capture as a template. */
    public record TemplateFromSelection(String captureId, String name, int x, int y, int width, int height,
                                        Double threshold, String screenContext, String s1Version) {}

    /** POST /authoring/api/captures/{id}/locate — find registered templates on this capture. */
    public record LocateCommand(List<String> templateRefs) {}

    /**
     * Where a template was found on a capture, and how well.
     *
     * @param score    best normalised cross-correlation score, 1.0 being pixel-identical
     * @param runnerUp best score elsewhere on the screen; close to {@code score} means the
     *                 template is not distinctive enough to act on
     * @param factor   the scale the template matched at — 1.0, or 1/uiScale when it was cropped
     *                 on a differently-scaled desktop
     */
    public record LocateResult(String templateRef, boolean found, int x, int y, int width, int height,
                               double score, double runnerUp, double factor, boolean ambiguous,
                               String diagnostic) {}

    /**
     * POST /authoring/api/locators — turn "this anchor, that point" into an offset locator.
     *
     * <p>The anchor is located on the capture server-side and the offset derived from where it
     * was actually found, so the saved numbers mean the same thing the engine will compute
     * (FR-SR-07). {@code targetX}/{@code targetY} are the author's click in capture pixels.</p>
     *
     * @param sizingTemplateRef optional template whose size gives the target region its extent;
     *                          without it the region is a point, which is what a click wants
     */
    public record OffsetLocatorCommand(String name, String captureId, String anchorTemplateRef,
                                       String sizingTemplateRef, int targetX, int targetY,
                                       Double threshold, String s1Version) {}

    /** The locator that was derived and saved, with the geometry the page draws. */
    public record OffsetLocatorView(String locatorId, String name, LocatorDefinition definition,
                                    LocateResult anchor, int targetX, int targetY,
                                    int regionX, int regionY, int regionWidth, int regionHeight) {}

    /** POST /authoring/api/test — resolve a locator against a freshly captured screen. */
    public record TestCommand(String workerId, boolean activateWindow, String label,
                              LocatorDefinition locator, Double threshold) {}

    /**
     * The outcome of a live test (FR-TOOL-04): a new capture, where the template was matched
     * on it, and the point the engine would act on.
     *
     * @param capture     the fresh screenshot the test ran against, for the page to display
     * @param match       where the matched template (or anchor) was found
     * @param region      the region the locator resolved to; null when it did not resolve
     * @param clickX      the point an action would target — the region's centre
     * @param elapsedMs   wall-clock resolution time (FR-TOOL-04)
     */
    public record TestResult(String label, boolean resolved, CaptureView capture, LocateResult match,
                             RegionView region, Integer clickX, Integer clickY, double threshold,
                             long elapsedMs, String diagnostic) {}

    public record RegionView(int x, int y, int width, int height) {}

    /** POST /authoring/api/captures/{id}/inspect — what is the accessible element at this point? */
    public record InspectCommand(int x, int y) {}

    /**
     * The element under the author's click, as the page shows it (FR-TOOL-03).
     *
     * <p>{@code matchCount} is the field that decides whether {@code locator} is worth saving.
     * The engine refuses to act on a locator that matches more than one element, so a count
     * above 1 means the suggestion identifies the control for a human but not for a run.</p>
     *
     * @param element    the element itself, in the capture's pixel space
     * @param ancestors  containers above it, outermost first
     * @param locator    a JAB locator that resolves to it, or null if nothing distinguishes it
     * @param matchCount how many elements that locator matches on the worker right now
     * @param stale      the live tree no longer agrees with the screenshot — the element was
     *                   found outside the capture's bounds, so the screen has moved on
     */
    public record InspectView(JabNode element, List<JabNode> ancestors, LocatorDefinition locator,
                              int matchCount, boolean stale) {}
}
