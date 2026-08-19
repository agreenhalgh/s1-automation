package com.newbrid.epr_automation.systmone.controlplane.amqp;

import tools.jackson.databind.ObjectMapper;
import com.newbrid.epr_automation.systmone.common.dto.RunJobMessage;
import com.newbrid.epr_automation.systmone.config.RabbitTopologyConfig;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Publishes a {@link RunJobMessage} to the work queue as a persistent JSON message
 * (FR-CP-01). Sending JSON text keeps the wire format independent of any broker-side
 * message-converter version.
 */
@Component
@Profile("controlplane")
public class JobPublisher {

    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;

    public JobPublisher(RabbitTemplate rabbit, ObjectMapper mapper) {
        this.rabbit = rabbit;
        this.mapper = mapper;
    }

    public void publish(RunJobMessage job) {
        try {
            String body = mapper.writeValueAsString(job);
            rabbit.convertAndSend(RabbitTopologyConfig.EXCHANGE, RabbitTopologyConfig.ROUTING_KEY, body, m -> {
                m.getMessageProperties().setContentType("application/json");
                m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                m.getMessageProperties().setHeader("runId", job.runId());
                return m;
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish job for run " + job.runId(), e);
        }
    }
}
