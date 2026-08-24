package com.newbrid.epr_automation.gateway.route;

import com.newbrid.epr_automation.gateway.audit.GatewayAuditEntity;
import com.newbrid.epr_automation.gateway.audit.GatewayAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway end to end, driven over real HTTP by a JDK client — no test harness shortcuts, so
 * what is asserted is what a calling system would see.
 *
 * <p>A fixed port rather than a random one: the route builds its control-plane endpoint URI when
 * the context starts, so the address of the stub has to be known before that happens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18083",
        "systmone.gateway.control-plane.url=http://localhost:18083",
        "systmone.gateway.inbound.token=test-gateway-token",
        "systmone.gateway.control-plane.token=test-worker-token",
        "systmone.gateway.audit.pseudonym-key=test-pseudonym-key",
        // Short enough that the give-up path is a fast test rather than a coffee break.
        "systmone.gateway.run.default-wait-seconds=2",
        "systmone.gateway.run.poll-interval-ms=100",
        "spring.datasource.url=jdbc:h2:mem:gateway;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(StubControlPlane.class)
class RunGatewayRouteTest {

    private static final String URL = "http://localhost:18083/gateway/v1/runs";

    /** A real NHS number shape with a correct modulus-11 check digit. */
    private static final String VALID_NHS = "9434765919";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Autowired
    private StubControlPlane.State stub;

    @Autowired
    private GatewayAuditRepository auditRepo;

    @BeforeEach
    void reset() {
        stub.reset();
        auditRepo.deleteAll();
    }

    // ---------------------------------------------------------------- the waiting contract

    @Test
    void answersOnlyOnceTheRunHasSucceeded() throws Exception {
        stub.statuses.addAll(List.of("PENDING", "RUNNING", "SUCCEEDED"));
        stub.outputsJson = "{\"postcode\":\"LS1 4AP\"}";

        HttpResponse<String> response = submit("""
                {"requestId":"caller-1","bot":"get-postcode","subject":{"nhsNumber":"%s"}}
                """.formatted(VALID_NHS));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"outcome\":\"SUCCEEDED\"")
                .contains("\"definitive\":true")
                .contains("\"runId\":\"run-under-test\"")
                .contains("\"requestId\":\"caller-1\"")
                .contains("LS1 4AP");
        assertThat(stub.polls.get())
                .as("it must have kept polling past the non-terminal statuses")
                .isGreaterThanOrEqualTo(3);
    }

    /**
     * A failed run is an answer, not an error. It comes back 200 with the outcome, because many
     * integration clients retry a 5xx automatically and re-running a clinical action that
     * legitimately failed is worse than reporting the failure.
     */
    @Test
    void aFailedRunIsReportedAsADefinitiveOutcomeNotAnHttpError() throws Exception {
        stub.statuses.addAll(List.of("RUNNING", "FAILED"));
        stub.errorType = "StepResolutionException";
        stub.errorMessage = "No locator in the chain resolved for step 'Open patient'";

        HttpResponse<String> response = submit("""
                {"bot":"get-postcode","subject":{"nhsNumber":"%s"}}
                """.formatted(VALID_NHS));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"outcome\":\"FAILED\"")
                .contains("\"definitive\":true")
                .contains("StepResolutionException")
                .contains("Open patient");
    }

    @Test
    void anEngineTimeoutIsADefinitiveOutcome() throws Exception {
        stub.statuses.addAll(List.of("RUNNING", "TIMED_OUT"));

        HttpResponse<String> response = submit("""
                {"bot":"get-postcode"}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"outcome\":\"TIMED_OUT\"")
                .as("the engine reached a terminal state, so this is a verdict on the run")
                .contains("\"definitive\":true");
    }

    /**
     * The gateway giving up is emphatically not the run timing out. It answers 504, says
     * {@code definitive: false}, and names the run so the caller can follow it up — because the
     * bot is very likely still typing into a patient record.
     */
    @Test
    void givingUpWaitingIsReportedAsSuchAndNotAsARunTimeout() throws Exception {
        stub.statuses.add("RUNNING");   // never settles

        HttpResponse<String> response = submit("""
                {"bot":"slow-bot"}
                """);

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(response.body())
                .contains("\"outcome\":\"GATEWAY_TIMEOUT\"")
                .contains("\"definitive\":false")
                .contains("run-under-test")
                .contains("may still be running");
        assertThat(response.body())
                .as("GATEWAY_TIMEOUT and the run's own TIMED_OUT must never be conflated")
                .doesNotContain("\"outcome\":\"TIMED_OUT\"");
    }

    // ---------------------------------------------------------------- transform

    @Test
    void transformsTheExternalContractIntoAControlPlaneDispatchRequest() throws Exception {
        stub.statuses.add("SUCCEEDED");

        submit("""
                {"bot":"get-postcode","pool":"ward-a",
                 "subject":{"nhsNumber":"%s","localId":"L-42"},
                 "parameters":{"searchTerm":"Smith"}}
                """.formatted(VALID_NHS));

        assertThat(stub.dispatchBodies).hasSize(1);
        String dispatched = stub.dispatchBodies.getFirst();
        assertThat(dispatched)
                .as("bot becomes botId and pool becomes capabilityTag")
                .contains("\"botId\":\"get-postcode\"")
                .contains("\"capabilityTag\":\"ward-a\"")
                .as("the subject is flattened into the input map the bot placeholders resolve against")
                .contains("\"subject.nhsNumber\":\"" + VALID_NHS + "\"")
                .contains("\"subject.localId\":\"L-42\"")
                .contains("\"searchTerm\":\"Smith\"");
        assertThat(stub.dispatchTokens.getFirst())
                .as("the control plane machine surface needs the worker token (FR-WRK-07)")
                .isEqualTo("test-worker-token");
    }

    // ---------------------------------------------------------------- validate

    @Test
    void rejectsASubmissionWithNoBot() throws Exception {
        HttpResponse<String> response = submit("""
                {"subject":{"nhsNumber":"%s"}}
                """.formatted(VALID_NHS));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("bot is required");
        assertThat(stub.dispatchBodies).as("nothing may be dispatched").isEmpty();
    }

    @Test
    void reportsEveryValidationProblemAtOnce() throws Exception {
        HttpResponse<String> response = submit("""
                {"bot":"bad bot name!","pool":"not a pool!","waitSeconds":99999}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .as("a caller with three bad fields should learn about three")
                .contains("bot").contains("pool").contains("waitSeconds");
    }

    @Test
    void rejectsAnNhsNumberThatFailsItsCheckDigit() throws Exception {
        HttpResponse<String> response = submit("""
                {"bot":"get-postcode","subject":{"nhsNumber":"9434765918"}}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("check digit");
        assertThat(stub.dispatchBodies).isEmpty();
    }

    @Test
    void rejectsAnUnknownFieldRatherThanSilentlyDroppingIt() throws Exception {
        HttpResponse<String> response = submit("""
                {"bot":"get-postcode","patientNhsNumber":"%s"}
                """.formatted(VALID_NHS));

        assertThat(response.statusCode())
                .as("a field sent by the wrong name would otherwise run a bot without it")
                .isEqualTo(400);
        assertThat(stub.dispatchBodies).isEmpty();
    }

    // ---------------------------------------------------------------- the front door

    @Test
    void refusesARequestWithNoToken() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"bot\":\"get-postcode\"}"))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(stub.dispatchBodies).isEmpty();
    }

    @Test
    void refusesAWrongToken() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                .header("Content-Type", "application/json")
                .header(GatewayExchange.GATEWAY_TOKEN_HEADER, "not-the-token")
                .POST(HttpRequest.BodyPublishers.ofString("{\"bot\":\"get-postcode\"}"))
                .build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
    }

    @Test
    void reportsAnUnreachableControlPlaneAsABadGatewayRatherThanAFailedRun() throws Exception {
        stub.dispatchStatus = 503;

        HttpResponse<String> response = submit("""
                {"bot":"get-postcode"}
                """);

        assertThat(response.statusCode())
                .as("the run was never created, so this is not an outcome")
                .isEqualTo(502);
        assertThat(response.body()).contains("control plane");
    }

    // ---------------------------------------------------------------- audit

    @Test
    void auditsEveryStageOfASuccessfulRequest() throws Exception {
        stub.statuses.addAll(List.of("RUNNING", "SUCCEEDED"));

        submit("""
                {"requestId":"caller-9","bot":"get-postcode","subject":{"nhsNumber":"%s"}}
                """.formatted(VALID_NHS));

        List<GatewayAuditEntity> trail = auditRepo.findAll();
        assertThat(trail).extracting(GatewayAuditEntity::getStage)
                .containsExactlyInAnyOrder("RECEIVED", "DISPATCHED", "SUCCEEDED");
        assertThat(trail).allSatisfy(row -> assertThat(row.getCorrelationId()).startsWith("gw-"));
        assertThat(trail).extracting(GatewayAuditEntity::getCorrelationId)
                .as("every row for one request shares its correlation id")
                .containsOnly(trail.getFirst().getCorrelationId());
        assertThat(trail).filteredOn(r -> "SUCCEEDED".equals(r.getStage()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getRunId()).isEqualTo("run-under-test");
                    assertThat(row.getHttpStatus()).isEqualTo(200);
                    assertThat(row.getElapsedMs()).isNotNull();
                });
    }

    /**
     * The audit trail must be able to say a request concerned a particular patient without
     * being a register of which patients were looked up (NFR-SEC-05).
     */
    @Test
    void auditsThePatientByReferenceAndNeverByIdentifier() throws Exception {
        stub.statuses.add("SUCCEEDED");

        submit("""
                {"bot":"get-postcode","subject":{"nhsNumber":"%s","localId":"L-77"},
                 "parameters":{"searchTerm":"Smith"}}
                """.formatted(VALID_NHS));

        List<GatewayAuditEntity> trail = auditRepo.findAll();
        assertThat(trail).isNotEmpty();

        String everything = trail.stream().map(String::valueOf).toList() + trail.stream()
                .map(r -> String.join("|", nullSafe(r.getSubjectRef()), nullSafe(r.getParameterKeys()),
                        nullSafe(r.getDetail()), nullSafe(r.getBotId()), nullSafe(r.getSource())))
                .reduce("", String::concat);

        assertThat(everything)
                .as("no NHS number may appear anywhere in the trail")
                .doesNotContain(VALID_NHS)
                .as("nor a local identifier")
                .doesNotContain("L-77")
                .as("nor a parameter value, which can carry clinical context")
                .doesNotContain("Smith");

        GatewayAuditEntity received = trail.stream()
                .filter(r -> "RECEIVED".equals(r.getStage())).findFirst().orElseThrow();
        assertThat(received.getSubjectRef())
                .as("but a stable reference to the patient is kept, so a trail can be followed")
                .isNotNull().startsWith("nhs-");
        assertThat(received.getParameterKeys())
                .as("parameter names are recorded; their values are not")
                .isEqualTo("searchTerm");
    }

    @Test
    void auditsARequestThatWasRefusedAtTheDoor() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"bot\":\"get-postcode\"}"))
                .build();
        http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(auditRepo.findAll())
                .as("a bad credential is exactly the event an integration owner needs recorded")
                .extracting(GatewayAuditEntity::getStage)
                .containsExactly("REJECTED");
        assertThat(auditRepo.findAll().getFirst().getHttpStatus()).isEqualTo(401);
    }

    @Test
    void auditsAGatewayTimeoutDistinctlyFromARunTimeout() throws Exception {
        stub.statuses.add("RUNNING");

        submit("""
                {"bot":"slow-bot"}
                """);

        assertThat(auditRepo.findAll()).extracting(GatewayAuditEntity::getStage)
                .as("the trail is the only record that a run was left in flight")
                .contains("GATEWAY_TIMEOUT")
                .doesNotContain("TIMED_OUT");
    }

    // ---------------------------------------------------------------- helpers

    private HttpResponse<String> submit(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                .header("Content-Type", "application/json")
                .header(GatewayExchange.GATEWAY_TOKEN_HEADER, "test-gateway-token")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
