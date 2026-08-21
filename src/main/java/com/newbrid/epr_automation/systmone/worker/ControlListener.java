package com.newbrid.epr_automation.systmone.worker;

import com.newbrid.epr_automation.systmone.automation.jab.JabInspector;
import com.newbrid.epr_automation.systmone.automation.screen.RobotInput;
import com.newbrid.epr_automation.systmone.common.dto.CaptureRequest;
import com.newbrid.epr_automation.systmone.common.dto.CaptureResponse;
import com.newbrid.epr_automation.systmone.common.dto.InspectRequest;
import com.newbrid.epr_automation.systmone.common.dto.InspectResponse;
import com.newbrid.epr_automation.systmone.sdk.engine.WindowControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Optional;

/**
 * Serves the authoring tool's requests (FR-TOOL-02/03) over this worker's control queue, as
 * request/reply: the reply goes straight back to the caller's reply-to queue. Two commands
 * arrive here — capture a screenshot, and inspect the accessible element under a point.
 *
 * <p>Capturing on the worker rather than the author's own machine is the whole point. The
 * template a bot matches must be cropped from the pixels <em>this</em> workstation produces —
 * its resolution, its desktop scaling, its SystmOne theme. A screenshot taken anywhere else
 * is a different image, and NCC has no scale invariance to save it. The same argument applies
 * twice over to inspection: the accessibility tree only exists in the SystmOne process on this
 * machine, and nothing about it can be recovered from an image after the fact.</p>
 *
 * <p>Unlike a job, neither command is queued for later: the queue is auto-delete, so requests
 * to an offline worker fail fast at the caller's reply timeout instead of arriving minutes
 * later against a screen that has moved on.</p>
 */
@Component
@Profile("worker")
public class ControlListener {

    private static final Logger log = LoggerFactory.getLogger(ControlListener.class);

    private final RobotInput input;
    private final WindowControl windows;
    private final JabInspector inspector;
    private final WorkerProperties props;
    private final ObjectMapper mapper;

    public ControlListener(RobotInput input, WindowControl windows, JabInspector inspector,
                           WorkerProperties props, ObjectMapper mapper) {
        this.input = input;
        this.windows = windows;
        this.inspector = inspector;
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * @return the response as JSON. Failures come back as a response carrying {@code error}
     *         rather than as an exception, so the author sees the reason in the tool instead
     *         of a timeout.
     */
    @RabbitListener(queues = "#{@controlQueue.name}")
    public String onControlMessage(String body) {
        String workerId = props.resolveWorkerId();
        try {
            JsonNode json = mapper.readTree(body);
            // A capture request carries no 'kind' at all, so its absence means capture. That is
            // what keeps the older, wider-deployed message valid without a version negotiation.
            String kind = json.path("kind").asString(null);
            if (InspectRequest.KIND.equals(kind) || InspectRequest.FOCUSED.equals(kind)) {
                return json(inspect(mapper.treeToValue(json, InspectRequest.class), workerId));
            }
            return json(capture(mapper.treeToValue(json, CaptureRequest.class), workerId));
        } catch (Exception e) {
            log.warn("Control request failed to parse: {}", e.toString());
            return json(CaptureResponse.failed(null, workerId, "Unreadable control request: " + e));
        }
    }

    // ---- capture ----

    private CaptureResponse capture(CaptureRequest req, String workerId) {
        String requestId = req.requestId();
        try {
            if (!input.isAvailable()) {
                return CaptureResponse.failed(requestId, workerId,
                        "This worker has no interactive desktop session (java.awt.Robot is "
                                + "unavailable), so it cannot capture the screen. Run the worker as a "
                                + "logged-in user with -Djava.awt.headless=false, not as a service.");
            }
            if (req.activateWindow()) {
                activateWindow();
            }

            BufferedImage screen = input.captureScreen();
            if (screen == null) {
                return CaptureResponse.failed(requestId, workerId, "Screen capture returned no image");
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(screen, "png", png);
            log.info("Captured {}x{} screenshot at {}x scaling for authoring request {}",
                    screen.getWidth(), screen.getHeight(), input.uiScale(), requestId);
            return new CaptureResponse(requestId, workerId, screen.getWidth(), screen.getHeight(),
                    input.uiScale(), props.getWindowTitle(),
                    Base64.getEncoder().encodeToString(png.toByteArray()), null);
        } catch (Exception e) {
            log.warn("Capture request {} failed: {}", requestId, e.toString());
            return CaptureResponse.failed(requestId, workerId, e.toString());
        }
    }

    /**
     * Best effort: an author asking for the SystmOne window wants it in front, but a capture
     * of the wrong window still tells them something, whereas a failed request tells them
     * nothing. The reason is logged rather than returned so the screenshot still arrives.
     */
    private void activateWindow() {
        try {
            log.info("Activated '{}' before capturing", windows.activate(props.getWindowTitle()));
        } catch (Exception e) {
            log.info("Could not bring '{}' to the front before capturing: {}",
                    props.getWindowTitle(), e.toString());
        }
    }

    // ---- inspect ----

    private InspectResponse inspect(InspectRequest req, String workerId) {
        String requestId = req.requestId();
        try {
            if (!inspector.isAvailable()) {
                return InspectResponse.failed(requestId, workerId,
                        "The Java Access Bridge is not available on this worker, so it cannot read "
                                + "SystmOne's accessibility tree. Run 'jabswitch -enable' for the JRE "
                                + "SystmOne runs under, then restart both SystmOne and this worker. "
                                + "Templates still work without it.");
            }
            Optional<JabInspector.Inspection> found = req.isFocusedRequest()
                    ? inspector.focused()
                    : inspector.inspect(req.x(), req.y());
            if (found.isEmpty()) {
                return InspectResponse.failed(requestId, workerId, req.isFocusedRequest()
                        ? "Nothing in a window matching '" + props.getWindowTitle() + "' reports the "
                                + "focused state. Click into a field or button in SystmOne, then ask "
                                + "again — a window with nothing focused has nothing to report."
                        : String.format("No accessible element at (%d,%d) in a window matching '%s'. "
                                + "Either that point is outside SystmOne, or the control is painted "
                                + "rather than built from accessible components — use a template there.",
                                req.x(), req.y(), props.getWindowTitle()));
            }
            JabInspector.Inspection i = found.get();
            log.info("Inspected {} for request {}: {} '{}', suggested locator matches {} element(s)",
                    req.isFocusedRequest() ? "the focused element"
                            : "(" + req.x() + "," + req.y() + ")",
                    requestId, i.element().role(), i.element().name(), i.matchCount());
            return new InspectResponse(requestId, workerId, i.element(), i.ancestors(),
                    i.previousSibling(), i.followingSibling(), i.suggested(), i.matchCount(), null);
        } catch (Exception e) {
            log.warn("Inspect request {} failed: {}", requestId, e.toString());
            return InspectResponse.failed(requestId, workerId, e.toString());
        }
    }

    // ---- wire ----

    private String json(Object response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            // Last resort: a hand-built payload, so the caller still gets a readable failure.
            return "{\"error\":\"could not serialise the worker's response: "
                    + e.getClass().getSimpleName() + "\"}";
        }
    }
}
