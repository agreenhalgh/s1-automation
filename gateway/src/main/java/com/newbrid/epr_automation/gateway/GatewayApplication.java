package com.newbrid.epr_automation.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The integration gateway: one Camel context in front of the control plane, turning an
 * asynchronous run into a synchronous request/reply for callers that need an answer rather than
 * a job id.
 *
 * <p>A separate process from the control plane by design. It holds a connection open for the
 * length of a run, which is a very different load profile from an operator UI, and it is the
 * component most likely to be exposed to another organisation's systems — neither of which
 * belongs in the same JVM as the run store.
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
