package com.newbrid.epr_automation.gateway.route;

import com.fasterxml.jackson.core.JacksonException;
import com.newbrid.epr_automation.gateway.GatewayProperties;
import com.newbrid.epr_automation.gateway.api.ApiProblem;
import com.newbrid.epr_automation.gateway.api.RunOutcome;
import com.newbrid.epr_automation.gateway.api.RunResponse;
import com.newbrid.epr_automation.gateway.api.RunSubmission;
import com.newbrid.epr_automation.gateway.audit.GatewayAuditService;
import com.newbrid.epr_automation.gateway.controlplane.ControlPlaneWire.DispatchResponse;
import com.newbrid.epr_automation.gateway.controlplane.ControlPlaneWire.RunLookup;
import com.newbrid.epr_automation.gateway.controlplane.ControlPlaneWire.RunView;
import com.newbrid.epr_automation.gateway.route.DocumentStore.StoredDocument;
import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.bean.validator.BeanValidationException;
import org.apache.camel.component.file.GenericFileOperationFailedException;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.stereotype.Component;

import jakarta.validation.ConstraintViolation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The gateway pipeline, as five routes.
 *
 * <pre>
 *   POST /gateway/v1/runs
 *     → authenticate, correlate            (gateway-submit)
 *     → parse, audit RECEIVED
 *     → validate  (JSR-380 + NHS check digit)
 *     → transform (external contract → control-plane DispatchRequest)
 *     → dispatch  POST /api/runs           (gateway-dispatch)
 *     → await     poll GET /api/runs/{id}  (gateway-await → gateway-poll)
 *     → audit terminal outcome, answer the caller
 * </pre>
 *
 * <h2>Why the caller waits</h2>
 * The control plane is asynchronous by design: dispatch returns a run id and the outcome arrives
 * later over a different path. Some callers cannot work that way — a clinician's system asking
 * "did that get filed?" needs an answer, not a job id and a callback contract. This gateway
 * absorbs that mismatch by polling on the caller's behalf and answering only once the run has
 * reached a terminal state.
 *
 * <h2>What the response means</h2>
 * A run that <em>failed</em> is a definitive answer, so it comes back <b>200</b> with
 * {@code outcome: FAILED}, not a 5xx. The HTTP status describes whether this gateway did its job;
 * the body describes what the run did. That matters more here than it would elsewhere: many
 * integration clients retry non-2xx automatically, and a clinical action must not be re-run
 * because the first attempt legitimately failed. Non-2xx is reserved for the cases where the
 * gateway has no answer to give — 400 rejected, 502 control plane unreachable, 504 gave up
 * waiting.
 *
 * <h2>Concurrency</h2>
 * Waiting costs a request thread for the duration, because the caller is holding a connection
 * open and there is nothing to hand it to. A gateway with the default 120s wait and Tomcat's
 * default 200 threads therefore saturates at roughly 200 concurrent runs — deliberately bounded
 * rather than accidentally so, and the reason {@code maxWaitSeconds} exists.
 */
@Component
public class RunGatewayRoute extends RouteBuilder {

    static final String ROUTE_SUBMIT = "gateway-submit";
    static final String ROUTE_DISPATCH = "gateway-dispatch";
    static final String ROUTE_AWAIT = "gateway-await";
    static final String ROUTE_POLL = "gateway-poll";
    static final String ROUTE_STORE_DOCUMENT = "gateway-store-document";

    /** Why the most recent read of the run failed, if it did. Reported if the wait then expires. */
    private static final String LAST_POLL_ERROR = "GwLastPollError";

    /** The document written for this request, once there is one. */
    private static final String STORED_DOCUMENT = "GwStoredDocument";

    private final GatewayProperties properties;
    private final GatewayAuditService audit;
    private final SubmissionTransformer transformer;
    private final DocumentStore documents;

    public RunGatewayRoute(GatewayProperties properties, GatewayAuditService audit,
                           SubmissionTransformer transformer, DocumentStore documents) {
        this.properties = properties;
        this.audit = audit;
        this.transformer = transformer;
        this.documents = documents;
    }

    @Override
    public void configure() {
        restConfiguration()
                .component("platform-http")
                // Explicit marshalling rather than automatic binding: the gateway owns the shape
                // of its error responses, and auto-binding would answer a malformed body with a
                // framework-generated message that no caller contract mentions.
                .bindingMode(RestBindingMode.off);

        errorRoutes();

        rest("/gateway/v1")
                .post("/runs").consumes("application/json").produces("application/json")
                .to("direct:" + ROUTE_SUBMIT);

        submitRoute();
        storeDocumentRoute();
        dispatchRoute();
        awaitRoutes();
    }

    // ------------------------------------------------------------------ the pipeline

    private void submitRoute() {
        from("direct:" + ROUTE_SUBMIT)
                .routeId(ROUTE_SUBMIT)
                .process(this::begin)
                .unmarshal().json(JsonLibrary.Jackson, RunSubmission.class)
                .process(this::afterParse)
                // Audited before validation, so a rejected request has a RECEIVED row to hang off
                // and the trail shows what was attempted rather than only what succeeded.
                .process(this::auditReceived)
                .to("bean-validator:runSubmission")
                .process(this::validateBeyondConstraints)
                // Stored before the run is dispatched, because the run is told where the file is
                // and cannot be told about a file that does not exist yet.
                .choice()
                    .when(this::hasDocument)
                        .to("direct:" + ROUTE_STORE_DOCUMENT)
                .end()
                .process(this::transform)
                .to("direct:" + ROUTE_DISPATCH)
                .to("direct:" + ROUTE_AWAIT)
                .process(this::settle)
                .marshal().json(JsonLibrary.Jackson);
    }

    /**
     * Writes a submitted PDF to the document store.
     *
     * <p>The endpoint is the storage <b>root</b> and never varies; the dated folder and filename
     * travel as {@code CamelFileName}, which Camel treats as a path relative to it. That is not
     * only tidier than building a dynamic endpoint per request — it means the one part of the path
     * that comes from the caller can never replace the part that does not.
     *
     * <p>{@code fileExist=Fail} rather than the default of overwriting. The filename already
     * carries a correlation id, so a collision means something is badly wrong; failing loudly on
     * one is much better than silently replacing a clinical document that has already been filed.
     */
    private void storeDocumentRoute() {
        from("direct:" + ROUTE_STORE_DOCUMENT)
                .routeId(ROUTE_STORE_DOCUMENT)
                .process(this::prepareDocument)
                .to("file:" + uriPath(documents.storageRoot()) + "?fileExist=Fail&autoCreate=true")
                .process(this::afterDocumentStored);
    }

    private void dispatchRoute() {
        String endpoint = properties.getControlPlane().getUrl() + "/api/runs";

        from("direct:" + ROUTE_DISPATCH)
                .routeId(ROUTE_DISPATCH)
                .marshal().json(JsonLibrary.Jackson)
                // Nothing the caller sent should reach the control plane as a header — not their
                // gateway token, not their content negotiation. Cleared rather than filtered so a
                // header added to the inbound contract later cannot start leaking by default.
                .removeHeaders("*")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
                .setHeader(GatewayExchange.WORKER_TOKEN_HEADER,
                        constant(properties.getControlPlane().getToken()))
                .to(endpoint)
                .unmarshal().json(JsonLibrary.Jackson, DispatchResponse.class)
                .process(this::afterDispatch);
    }

    private void awaitRoutes() {
        String pollEndpoint = properties.getControlPlane().getUrl()
                + "/api/runs/${exchangeProperty." + GatewayExchange.RUN_ID + "}"
                // A blip reading the run must not cost the caller their answer, so a non-200 is
                // recorded and retried until the deadline rather than thrown. The status of the
                // last successful read is what decides the outcome.
                + "?throwExceptionOnFailure=false";

        from("direct:" + ROUTE_AWAIT)
                .routeId(ROUTE_AWAIT)
                // Checked once immediately, then on an interval. Polling first means the loop
                // predicate always sees a status that was actually read.
                .to("direct:" + ROUTE_POLL)
                .loopDoWhile(stillWaiting())
                    .delay(properties.getRun().getPollIntervalMs())
                    .to("direct:" + ROUTE_POLL)
                .end();

        from("direct:" + ROUTE_POLL)
                .routeId(ROUTE_POLL)
                .removeHeaders("*")
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                .setHeader(GatewayExchange.WORKER_TOKEN_HEADER,
                        constant(properties.getControlPlane().getToken()))
                .toD(pollEndpoint)
                // Only a 200 carries a run to unmarshal. On anything else the body is an error
                // document, and handing that to the run data format would turn a transient blip
                // into a parse failure that ends the wait.
                .choice()
                    .when(header(Exchange.HTTP_RESPONSE_CODE).isEqualTo(200))
                        .unmarshal().json(JsonLibrary.Jackson, RunLookup.class)
                        .process(this::recordPollResult)
                    .otherwise()
                        .process(this::recordPollFailure)
                .end();
    }

    /**
     * Keep waiting while the run has not settled and the deadline has not passed. Evaluated
     * before each further poll, so the loop exits the moment either becomes true.
     */
    private Predicate stillWaiting() {
        return exchange -> {
            String status = exchange.getProperty(GatewayExchange.RUN_STATUS, String.class);
            long deadline = exchange.getProperty(GatewayExchange.DEADLINE_MS, 0L, Long.class);
            return !RunOutcome.isTerminalStatus(status) && System.currentTimeMillis() < deadline;
        };
    }

    // ------------------------------------------------------------------ steps

    /** Authenticate and correlate, before anything is parsed or stored. */
    private void begin(Exchange exchange) throws SubmissionRejectedException {
        exchange.setProperty(GatewayExchange.STARTED_NANOS, System.nanoTime());

        String correlationId = "gw-" + UUID.randomUUID();
        exchange.setProperty(GatewayExchange.CORRELATION_ID, correlationId);
        exchange.getMessage().setHeader(GatewayExchange.CORRELATION_ID_HEADER, correlationId);

        String source = exchange.getMessage().getHeader("X-Forwarded-For", String.class);
        exchange.setProperty(GatewayExchange.SOURCE, source == null ? "unknown" : source);

        String presented = exchange.getMessage()
                .getHeader(GatewayExchange.GATEWAY_TOKEN_HEADER, String.class);
        String expected = properties.getInbound().getToken();
        // Constant-time comparison: this is a bearer credential, and a length-or-prefix-dependent
        // comparison on one is a timing oracle. Cheap to do properly.
        if (presented == null || !java.security.MessageDigest.isEqual(
                presented.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new SubmissionRejectedException(401,
                    "Missing or invalid " + GatewayExchange.GATEWAY_TOKEN_HEADER);
        }
    }

    /** Stash the parsed submission and work out when to stop waiting for it. */
    private void afterParse(Exchange exchange) {
        RunSubmission submission = exchange.getMessage().getBody(RunSubmission.class);
        exchange.setProperty(GatewayExchange.SUBMISSION, submission);
        exchange.setProperty(GatewayExchange.CLIENT_REQUEST_ID, submission.requestId());
        exchange.setProperty(GatewayExchange.DEADLINE_MS,
                System.currentTimeMillis() + transformer.waitMillisFor(submission));
    }

    private void auditReceived(Exchange exchange) {
        audit.received(exchange.getProperty(GatewayExchange.CORRELATION_ID, String.class),
                exchange.getProperty(GatewayExchange.SOURCE, String.class),
                exchange.getProperty(GatewayExchange.SUBMISSION, RunSubmission.class));
    }

    /**
     * The checks annotations cannot express. Only the NHS check digit today, but this is where
     * anything needing more than one field, or a lookup, belongs.
     */
    private void validateBeyondConstraints(Exchange exchange) throws SubmissionRejectedException {
        RunSubmission submission = exchange.getProperty(GatewayExchange.SUBMISSION, RunSubmission.class);
        RunSubmission.Subject subject = submission.subject();
        if (subject == null || subject.nhsNumber() == null || subject.nhsNumber().isBlank()) {
            return;
        }
        if (!SubmissionTransformer.hasValidNhsCheckDigit(subject.nhsNumber())) {
            throw new SubmissionRejectedException(400, "Submission failed validation",
                    List.of("subject.nhsNumber: fails the NHS number check digit"));
        }
    }

    /** Whether this submission carries a document at all */
    private boolean hasDocument(Exchange exchange) {
        RunSubmission submission =
                exchange.getProperty(GatewayExchange.SUBMISSION, RunSubmission.class);
        return submission != null && submission.hasDocument();
    }

    /**
     * Validate the document, decide where it goes, and put the bytes in the body for the file
     * component to write. The submission is already parked in an exchange property, so replacing
     * the body costs nothing and {@link #afterDocumentStored} puts it back.
     */
    private void prepareDocument(Exchange exchange) throws SubmissionRejectedException {
        RunSubmission submission =
                exchange.getProperty(GatewayExchange.SUBMISSION, RunSubmission.class);
        String correlationId = exchange.getProperty(GatewayExchange.CORRELATION_ID, String.class);

        StoredDocument document = documents.prepare(submission, correlationId);
        exchange.setProperty(STORED_DOCUMENT, document);

        exchange.getMessage().setBody(document.content());
        // Relative to the endpoint's root: "<yyyy-MM-dd>/<user._id>-<correlationId>.pdf".
        exchange.getMessage().setHeader(Exchange.FILE_NAME,
                uriPath(documents.storageRoot().relativize(document.path())));
    }

    /** Audit the write and restore the submission as the body for the transform step. */
    private void afterDocumentStored(Exchange exchange) {
        StoredDocument document = exchange.getProperty(STORED_DOCUMENT, StoredDocument.class);
        RunSubmission submission =
                exchange.getProperty(GatewayExchange.SUBMISSION, RunSubmission.class);

        audit.documentStored(exchange.getProperty(GatewayExchange.CORRELATION_ID, String.class),
                submission.requestId(), submission.bot(), submission.user().id(),
                document.fileName(), document.path().toString(), document.bytes(),
                document.sha256(), elapsedMs(exchange));

        exchange.getMessage().setBody(submission);
    }

    /**
     * The transform step. A processor rather than {@code .bean()} because the transform needs the
     * stored document as well as the submission, and only the exchange knows about both.
     */
    private void transform(Exchange exchange) {
        RunSubmission submission =
                exchange.getProperty(GatewayExchange.SUBMISSION, RunSubmission.class);
        StoredDocument document = exchange.getProperty(STORED_DOCUMENT, StoredDocument.class);
        exchange.getMessage().setBody(transformer.toDispatchRequest(submission, document));
    }

    private void afterDispatch(Exchange exchange) {
        DispatchResponse response = exchange.getMessage().getBody(DispatchResponse.class);
        exchange.setProperty(GatewayExchange.RUN_ID, response.runId());
        // Non-terminal on purpose: the run exists but has not been read yet, and the loop
        // predicate must see a status that cannot be mistaken for an outcome.
        exchange.setProperty(GatewayExchange.RUN_STATUS, "DISPATCHED");

        RunSubmission submission = exchange.getProperty(GatewayExchange.SUBMISSION, RunSubmission.class);
        audit.dispatched(exchange.getProperty(GatewayExchange.CORRELATION_ID, String.class),
                submission.requestId(), submission.bot(), submission.pool(),
                response.runId(), elapsedMs(exchange));
    }

    /** One successful read of the run. Its status is what the loop predicate tests next. */
    private void recordPollResult(Exchange exchange) {
        RunLookup lookup = exchange.getMessage().getBody(RunLookup.class);
        if (lookup == null || lookup.run() == null) {
            exchange.setProperty(LAST_POLL_ERROR, "control plane returned no run in the response");
            return;
        }
        exchange.removeProperty(LAST_POLL_ERROR);
        exchange.setProperty(GatewayExchange.RUN_VIEW, lookup.run());
        exchange.setProperty(GatewayExchange.RUN_STATUS, lookup.run().status());
    }

    /**
     * A read that did not come back 200. Deliberately not fatal: the status is left as it was, so
     * the loop tries again until the deadline. A caller loses their answer to a five-second blip
     * otherwise, and the run is still running regardless.
     */
    private void recordPollFailure(Exchange exchange) {
        Integer status = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
        exchange.setProperty(LAST_POLL_ERROR,
                "control plane returned HTTP " + status + " reading the run");
    }

    /** Turn whatever state the wait ended in into the caller's answer, and audit it. */
    private void settle(Exchange exchange) {
        String status = exchange.getProperty(GatewayExchange.RUN_STATUS, String.class);
        RunView run = exchange.getProperty(GatewayExchange.RUN_VIEW, RunView.class);
        RunSubmission submission = exchange.getProperty(GatewayExchange.SUBMISSION, RunSubmission.class);
        String runId = exchange.getProperty(GatewayExchange.RUN_ID, String.class);
        long elapsed = elapsedMs(exchange);

        boolean settled = RunOutcome.isTerminalStatus(status);
        RunOutcome outcome = settled ? RunOutcome.ofStatus(status) : RunOutcome.GATEWAY_TIMEOUT;
        int httpStatus = settled ? 200 : 504;

        RunResponse.Builder body = RunResponse.builder()
                .requestId(submission.requestId())
                .correlationId(exchange.getProperty(GatewayExchange.CORRELATION_ID, String.class))
                .runId(runId)
                .outcome(outcome)
                .waitedMs(elapsed);

        if (run != null) {
            body.startedAt(run.startedAt()).finishedAt(run.finishedAt());
            if (run.errorType() != null || run.errorMessage() != null) {
                body.error(new RunResponse.ErrorDetail(run.errorType(), run.errorMessage()));
            }
            if (outcome == RunOutcome.SUCCEEDED) {
                body.outputs(OutputsCodec.decode(run.outputsJson()));
            }
        }
        if (!settled) {
            String pollError = exchange.getProperty(LAST_POLL_ERROR, String.class);
            // Says plainly that nothing has been decided. A caller that treats this as a failure
            // will double-run a clinical action, so the message has to be unambiguous.
            body.message("The gateway stopped waiting after " + elapsed + "ms; run " + runId
                    + " was still " + status + " and may still be running. Poll /api/runs/" + runId
                    + " for its outcome."
                    + (pollError == null ? "" : " Last read failed: " + pollError));
        }

        audit.settled(exchange.getProperty(GatewayExchange.CORRELATION_ID, String.class),
                submission.requestId(), submission.bot(), runId, outcome, httpStatus, elapsed,
                settled ? null : "wait abandoned while status was " + status);

        exchange.getMessage().setBody(body.build());
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, httpStatus);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
    }

    // ------------------------------------------------------------------ failure paths

    /**
     * One handler per failure the gateway can distinguish, each producing the same problem shape
     * and each auditing before it answers. {@code handled(true)} throughout: the caller gets a
     * documented response rather than a stack trace, and the trail records what they were told.
     */
    private void errorRoutes() {
        onException(SubmissionRejectedException.class)
                .handled(true)
                .process(exchange -> {
                    SubmissionRejectedException ex = caught(exchange, SubmissionRejectedException.class);
                    reject(exchange, ex.httpStatus(), ex.getMessage(), ex.problems());
                })
                .marshal().json(JsonLibrary.Jackson);

        onException(BeanValidationException.class)
                .handled(true)
                .process(exchange -> {
                    BeanValidationException ex = caught(exchange, BeanValidationException.class);
                    reject(exchange, 400, "Submission failed validation", describe(ex));
                })
                .marshal().json(JsonLibrary.Jackson);

        // An unreadable or unexpected body. 400 rather than 500: the request is at fault, and
        // RunSubmission rejects unknown fields deliberately, so this is the response a caller
        // sending a field by the wrong name gets.
        onException(JacksonException.class)
                .handled(true)
                .process(exchange -> reject(exchange, 400,
                        "Request body is not a valid run submission",
                        List.of(rootMessage(exchange))))
                .marshal().json(JsonLibrary.Jackson);

        // The control plane refused or could not be reached. The run was never created, so
        // nothing is in flight and the caller is safe to retry.
        onException(HttpOperationFailedException.class)
                .handled(true)
                .process(exchange -> {
                    HttpOperationFailedException ex = caught(exchange, HttpOperationFailedException.class);
                    reject(exchange, 502, "The control plane rejected the run request",
                            List.of("control plane returned HTTP " + ex.getStatusCode()));
                })
                .marshal().json(JsonLibrary.Jackson);

        // The document could not be written — no space, no permission, or a name collision that
        // fileExist=Fail refused to overwrite. Distinct from the control-plane failures below
        // because the caller has to know the document was not stored: they must not assume it has
        // been filed, and the generic message would have told them the wrong thing.
        onException(GenericFileOperationFailedException.class)
                .handled(true)
                .process(exchange -> reject(exchange, 500,
                        "The document could not be stored, so no run was dispatched",
                        List.of(rootMessage(exchange))))
                .marshal().json(JsonLibrary.Jackson);

        onException(Exception.class)
                .handled(true)
                .process(exchange -> reject(exchange, 502,
                        "The run request could not be placed with the control plane",
                        List.of(rootMessage(exchange))))
                .marshal().json(JsonLibrary.Jackson);
    }

    /** Audit the rejection and set the response, so every failure path leaves the same trace. */
    private void reject(Exchange exchange, int httpStatus, String message, List<String> problems) {
        String correlationId = exchange.getProperty(GatewayExchange.CORRELATION_ID, String.class);
        String clientRequestId = exchange.getProperty(GatewayExchange.CLIENT_REQUEST_ID, String.class);
        String runId = exchange.getProperty(GatewayExchange.RUN_ID, String.class);
        long elapsed = elapsedMs(exchange);

        audit.rejected(correlationId, clientRequestId,
                exchange.getProperty(GatewayExchange.SOURCE, String.class),
                httpStatus, message + (problems.isEmpty() ? "" : " " + problems), elapsed);

        exchange.getMessage().setBody(new ApiProblem(message, correlationId, clientRequestId,
                runId, problems));
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, httpStatus);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        if (correlationId != null) {
            exchange.getMessage().setHeader(GatewayExchange.CORRELATION_ID_HEADER, correlationId);
        }
    }

    /** Constraint violations as {@code field: message}, sorted so the output is stable. */
    private static List<String> describe(BeanValidationException ex) {
        List<String> problems = new ArrayList<>();
        for (ConstraintViolation<Object> violation : ex.getConstraintViolations()) {
            problems.add(violation.getPropertyPath() + ": " + violation.getMessage());
        }
        problems.sort(Comparator.naturalOrder());
        return problems;
    }

    private static <T extends Throwable> T caught(Exchange exchange, Class<T> type) {
        return exchange.getProperty(Exchange.EXCEPTION_CAUGHT, type);
    }

    private static String rootMessage(Exchange exchange) {
        Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        if (cause == null) {
            return "unknown error";
        }
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /**
     * Forward slashes, whatever the platform. A Windows path goes into a Camel endpoint URI and
     * into a file name header, and a backslash is an escape in the first and merely unportable in
     * the second. Both Camel and the JDK accept forward slashes on Windows.
     */
    private static String uriPath(java.nio.file.Path path) {
        return path.toString().replace(java.io.File.separatorChar, '/');
    }

    private static long elapsedMs(Exchange exchange) {
        Long started = exchange.getProperty(GatewayExchange.STARTED_NANOS, Long.class);
        return started == null ? 0L : (System.nanoTime() - started) / 1_000_000L;
    }
}
