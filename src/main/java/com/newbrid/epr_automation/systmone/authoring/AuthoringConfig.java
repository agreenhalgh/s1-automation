package com.newbrid.epr_automation.systmone.authoring;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Assembles the capture tool (FR-TOOL-02/04): a web UI, an HTTP client to the control-plane
 * registry, and an AMQP request/reply channel to workers. It owns no database — everything it
 * stores, it stores through the control plane, and the screenshots it works on stay in memory.
 */
@Configuration
@Profile("authoring")
@EnableConfigurationProperties(AuthoringProperties.class)
public class AuthoringConfig {

    /**
     * Defining a template here replaces Boot's auto-configured one, which is what allows the
     * reply timeout to be set: {@code convertSendAndReceive} takes its timeout from the template,
     * and the default would leave an author waiting on a stopped worker for five seconds with no
     * explanation of which worker or why.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, AuthoringProperties props) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setReplyTimeout(props.getCaptureTimeoutMs());
        return template;
    }
}
