package com.newbrid.epr_automation.systmone.worker;

import com.newbrid.epr_automation.systmone.automation.screen.RobotInput;
import com.newbrid.epr_automation.systmone.common.dto.CaptureRequest;
import com.newbrid.epr_automation.systmone.common.dto.CaptureResponse;
import com.newbrid.epr_automation.systmone.sdk.engine.WindowControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Serves screenshot requests from the authoring tool (FR-TOOL-02) over this worker's control
 * queue, as request/reply: the reply goes straight back to the caller's reply-to queue.
 *
 * <p>Capturing on the worker rather than the author's own machine is the whole point. The
 * template a bot matches must be cropped from the pixels <em>this</em> workstation produces —
 * its resolution, its desktop scaling, its SystmOne theme. A screenshot taken anywhere else
 * is a different image, and NCC has no scale invariance to save it.</p>
 *
 * <p>Unlike a job, a capture is not queued for later: the queue is auto-delete, so requests
 * to an offline worker fail fast at the caller's reply timeout instead of arriving minutes
 * later against a screen that has moved on.</p>
 */
@Component
@Profile("worker")
public class CaptureListener {

    private static final Logger log = LoggerFactory.getLogger(CaptureListener.class);

    private final RobotInput input;
    private final WindowControl windows;
    private final WorkerProperties props;
    private final ObjectMapper mapper;

    public CaptureListener(RobotInput input, WindowControl windows, WorkerProperties props,
                           ObjectMapper mapper) {
        this.input = input;
        this.windows = windows;
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * @return the {@link CaptureResponse} as JSON. Failures come back as a response carrying
     *         {@code error} rather than as an exception, so the author sees the reason in the
     *         tool instead of a timeout.
     */
    @RabbitListener(queues = "#{@controlQueue.name}")
    public String onCapture(String body) {
        String workerId = props.resolveWorkerId();
        String requestId = null;
        try {
            CaptureRequest req = mapper.readValue(body, CaptureRequest.class);
            requestId = req.requestId();

            if (!input.isAvailable()) {
                return json(CaptureResponse.failed(requestId, workerId,
                        "This worker has no interactive desktop session (java.awt.Robot is "
                                + "unavailable), so it cannot capture the screen. Run the worker as a "
                                + "logged-in user with -Djava.awt.headless=false, not as a service."));
            }
            if (req.activateWindow()) {
                activateWindow();
            }

            BufferedImage screen = input.captureScreen();
            if (screen == null) {
                return json(CaptureResponse.failed(requestId, workerId, "Screen capture returned no image"));
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(screen, "png", png);
            log.info("Captured {}x{} screenshot at {}x scaling for authoring request {}",
                    screen.getWidth(), screen.getHeight(), input.uiScale(), requestId);
            return json(new CaptureResponse(requestId, workerId, screen.getWidth(), screen.getHeight(),
                    input.uiScale(), props.getWindowTitle(),
                    Base64.getEncoder().encodeToString(png.toByteArray()), null));
        } catch (Exception e) {
            log.warn("Capture request {} failed: {}", requestId, e.toString());
            return json(CaptureResponse.failed(requestId, workerId, e.toString()));
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

    private String json(CaptureResponse response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            // Last resort: a hand-built payload, so the caller still gets a readable failure.
            return "{\"workerId\":\"" + response.workerId() + "\",\"error\":\"could not serialise "
                    + "the capture response: " + e.getClass().getSimpleName() + "\"}";
        }
    }
}
