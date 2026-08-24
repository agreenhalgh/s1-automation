package com.newbrid.epr_automation.gateway.route;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A control plane, as far as the gateway can tell: the two endpoints it actually calls, served
 * over real HTTP on the same port the gateway is listening on.
 *
 * <p>Real HTTP rather than a mocked bean, because most of what can go wrong in this gateway is in
 * the wiring — headers leaking from one call to the next, a poll loop that never terminates, a
 * body that will not unmarshal. A stubbed Java method would exercise none of it.
 */
@TestConfiguration
public class StubControlPlane {

    /** What the stub should say, and what it was asked, for a test to arrange and assert. */
    public static class State {

        /** Bodies received on POST /api/runs, in order. */
        public final List<String> dispatchBodies = new ArrayList<>();

        /** Tokens presented on POST /api/runs, in order. */
        public final List<String> dispatchTokens = new ArrayList<>();

        /**
         * Statuses to return from successive polls. The last one is repeated once exhausted, so a
         * test that wants "never settles" pushes a single non-terminal status.
         */
        public final Deque<String> statuses = new ArrayDeque<>();

        public final AtomicInteger polls = new AtomicInteger();

        public String runId = "run-under-test";
        public String outputsJson = null;
        public String errorType = null;
        public String errorMessage = null;

        /** Status POST /api/runs answers with, for exercising the unreachable-control-plane path. */
        public int dispatchStatus = 200;

        /** Status GET /api/runs/{id} answers with. */
        public int lookupStatus = 200;

        public void reset() {
            dispatchBodies.clear();
            dispatchTokens.clear();
            statuses.clear();
            polls.set(0);
            runId = "run-under-test";
            outputsJson = null;
            errorType = null;
            errorMessage = null;
            dispatchStatus = 200;
            lookupStatus = 200;
        }

        /** The status this poll should see, holding on the last once the queue runs dry. */
        String nextStatus() {
            polls.incrementAndGet();
            if (statuses.isEmpty()) {
                return "RUNNING";
            }
            return statuses.size() == 1 ? statuses.peek() : statuses.poll();
        }
    }

    @Bean
    public State stubControlPlaneState() {
        return new State();
    }

    @Bean
    public RouteBuilder stubControlPlaneRoutes(State state) {
        return new RouteBuilder() {
            @Override
            public void configure() {
                rest("/api")
                        .post("/runs").to("direct:stub-dispatch")
                        .get("/runs/{id}").to("direct:stub-lookup");

                from("direct:stub-dispatch")
                        .routeId("stub-dispatch")
                        .process(exchange -> {
                            state.dispatchBodies.add(exchange.getMessage().getBody(String.class));
                            state.dispatchTokens.add(exchange.getMessage()
                                    .getHeader(GatewayExchange.WORKER_TOKEN_HEADER, String.class));
                            if (state.dispatchStatus != 200) {
                                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE,
                                        state.dispatchStatus);
                                exchange.getMessage().setBody("{\"error\":\"stubbed failure\"}");
                                return;
                            }
                            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
                            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
                            exchange.getMessage().setBody("{\"runId\":\"" + state.runId + "\"}");
                        });

                from("direct:stub-lookup")
                        .routeId("stub-lookup")
                        .process(exchange -> {
                            if (state.lookupStatus != 200) {
                                state.polls.incrementAndGet();
                                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE,
                                        state.lookupStatus);
                                exchange.getMessage().setBody("{}");
                                return;
                            }
                            String status = state.nextStatus();
                            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
                            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
                            exchange.getMessage().setBody(runJson(state, status));
                        });
            }
        };
    }

    /** Shaped like the control plane's RunDetail: a run, and steps the gateway ignores. */
    private static String runJson(State state, String status) {
        StringBuilder run = new StringBuilder("{\"run\":{")
                .append("\"id\":\"").append(state.runId).append("\",")
                .append("\"botId\":\"bot-under-test\",")
                .append("\"status\":\"").append(status).append("\",")
                .append("\"workerId\":\"worker-1\",")
                .append("\"createdAt\":\"2026-08-24T09:00:00Z\",")
                .append("\"startedAt\":\"2026-08-24T09:00:01Z\",")
                .append("\"finishedAt\":\"2026-08-24T09:00:09Z\"");
        if (state.errorType != null) {
            run.append(",\"errorType\":\"").append(state.errorType).append("\"")
               .append(",\"errorMessage\":\"").append(state.errorMessage).append("\"");
        }
        if (state.outputsJson != null) {
            run.append(",\"outputsJson\":").append(quote(state.outputsJson));
        }
        // A field the gateway has never heard of, proving it ignores what it does not need.
        run.append(",\"someFieldAddedLater\":true}");
        run.append(",\"steps\":[]}");
        return run.toString();
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
