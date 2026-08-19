package com.newbrid.epr_automation.systmone.authoring;

import com.newbrid.epr_automation.systmone.common.dto.CaptureRequest;
import com.newbrid.epr_automation.systmone.common.dto.CaptureResponse;
import com.newbrid.epr_automation.systmone.config.RabbitTopologyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Asks one named worker for a screenshot, over the control exchange, and waits for the reply
 * (FR-TOOL-02).
 *
 * <p>Request/reply rather than fire-and-forget: the author is watching a screen and wants
 * <em>that</em> screen. The message is transient and expires — a capture that arrives after
 * the author has moved on is worse than none, because it would be cropped as if it were
 * current.</p>
 */
@Component
@Profile("authoring")
public class CaptureClient {

    private static final Logger log = LoggerFactory.getLogger(CaptureClient.class);

    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    private final AuthoringProperties props;

    public CaptureClient(RabbitTemplate rabbit, ObjectMapper mapper, AuthoringProperties props) {
        this.rabbit = rabbit;
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * @return the worker's response; never null. A worker that is down, has no control queue
     *         bound, or does not answer in time comes back as a response carrying {@code error}
     *         so the tool can say which of those it was.
     */
    public CaptureResponse capture(String workerId, boolean activateWindow) {
        String requestId = UUID.randomUUID().toString();
        int timeout = props.getCaptureTimeoutMs();
        try {
            String body = mapper.writeValueAsString(new CaptureRequest(requestId, activateWindow));
            Object reply = rabbit.convertSendAndReceive(RabbitTopologyConfig.CONTROL_EXCHANGE, workerId,
                    body, m -> {
                        m.getMessageProperties().setContentType("application/json");
                        m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT);
                        m.getMessageProperties().setExpiration(String.valueOf(timeout));
                        return m;
                    });
            if (reply == null) {
                return CaptureResponse.failed(requestId, workerId, "Worker '" + workerId
                        + "' did not answer within " + timeout + "ms. It may be stopped, pointed at "
                        + "another broker, or busy with a run that is holding the desktop.");
            }
            return mapper.readValue(reply.toString(), CaptureResponse.class);
        } catch (Exception e) {
            log.warn("Capture request to worker {} failed: {}", workerId, e.toString());
            return CaptureResponse.failed(requestId, workerId,
                    "Could not reach worker '" + workerId + "': " + e.getMessage());
        }
    }
}
