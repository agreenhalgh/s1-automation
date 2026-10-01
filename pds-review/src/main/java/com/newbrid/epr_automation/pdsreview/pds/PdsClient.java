package com.newbrid.epr_automation.pdsreview.pds;

import com.newbrid.epr_automation.pdsreview.client.CallContext;
import com.newbrid.epr_automation.pdsreview.client.CallThrottle;
import com.newbrid.epr_automation.pdsreview.config.PdsReviewProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Talks to the Personal Demographics Service.
 *
 * <p>Two behaviours here are requirements rather than implementation detail, and both are about
 * not lying to the reviewer:
 *
 * <ul>
 *   <li><b>An error is never a nil result.</b> A timeout, a 500 or a refused token comes back as
 *       {@link com.newbrid.epr_automation.pdsreview.domain.SearchOutcome#ERROR}, never as "no
 *       matches found" (FR-PDS-12). The two lead to opposite decisions, and conflating them is
 *       how "accepted — not on PDS" gets recorded about a patient PDS was never asked about.</li>
 *   <li><b>TOO_MANY_MATCHES is its own answer.</b> Past its cap PDS returns <em>no</em> results
 *       rather than a truncated list, so reporting that as an empty search would be exactly
 *       backwards — there were too many, not none (FR-PDS-09).</li>
 * </ul>
 *
 * <p>{@code _exact-match} is never sent (FR-PDS-08): restricting results to a score of 1.0 would
 * hide the near-misses a human is here to adjudicate.
 */
@Component
public class PdsClient {

    private static final Logger log = LoggerFactory.getLogger(PdsClient.class);
    private static final JsonMapper JSON = new JsonMapper();

    /** PDS's code for "more matches than I will return". */
    private static final String TOO_MANY_MATCHES = "TOO_MANY_MATCHES";

    private final RestClient http;
    private final CallThrottle throttle;
    private final PdsReviewProperties.Pds config;
    private final boolean configured;

    public PdsClient(PdsReviewProperties properties) {
        this.config = properties.pds();
        this.configured = config.baseUrl() != null && !config.baseUrl().isBlank();
        this.throttle = new CallThrottle(config.requestsPerSecond());

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) config.connectTimeout().toMillis());
        factory.setReadTimeout((int) config.readTimeout().toMillis());
        this.http = RestClient.builder().requestFactory(factory).build();

        if (!configured) {
            log.warn("No PDS base URL is configured. Searches will report an error rather than "
                    + "an empty result, so a reviewer is never shown 'no matches' for a search "
                    + "that never happened.");
        }
    }

    /** Whether PDS can be reached at all. False makes every search an explicit error. */
    public boolean configured() {
        return configured;
    }

    public int maxResults() {
        return config.maxResults();
    }

    /**
     * Runs a search. Never throws: every failure is an outcome the reviewer can be shown, because
     * an exception surfacing as a stack trace on a clinical screen tells them nothing about
     * whether the patient exists.
     */
    public PdsSearchResult search(PdsSearchParameters parameters, CallContext context) {
        List<String> problems = parameters.problems();
        if (!problems.isEmpty()) {
            return PdsSearchResult.notAttempted(String.join("; ", problems));
        }
        if (!configured) {
            return PdsSearchResult.error("PDS is not configured in this environment", 0L);
        }

        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(config.baseUrl())
                .path("/Patient");
        parameters.query().forEach(uri::queryParam);

        long started = System.nanoTime();
        throttle.acquire();
        try {
            return http.get()
                    .uri(uri.build().toUri())
                    .headers(headers -> applyHeaders(headers, context))
                    .exchange((request, response) -> read(response.getStatusCode(),
                            body(response), elapsedMs(started)), false);
        } catch (Exception e) {
            // Includes connect and read timeouts. Reported as an error, deliberately not as a
            // nil result.
            log.warn("PDS search failed: {}", e.toString());
            return PdsSearchResult.error("PDS could not be reached: " + reason(e), elapsedMs(started));
        }
    }

    /**
     * Retrieves one candidate in full, so a decision can name the exact version of the record it
     * was made against (FR-PDS-11).
     */
    public Optional<PdsCandidate> retrieve(String nhsNumber, CallContext context) {
        if (!configured || nhsNumber == null || nhsNumber.isBlank()) {
            return Optional.empty();
        }
        URI uri = UriComponentsBuilder.fromUriString(config.baseUrl())
                .path("/Patient/{nhsNumber}")
                .build(nhsNumber);

        throttle.acquire();
        try {
            return http.get()
                    .uri(uri)
                    .headers(headers -> applyHeaders(headers, context))
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return Optional.<PdsCandidate>empty();
                        }
                        JsonNode patient = JSON.readTree(body(response));
                        return Optional.of(PdsCandidate.fromEntry(
                                JSON.createObjectNode().set("resource", patient),
                                patient.toString()));
                    }, false);
        } catch (Exception e) {
            log.warn("PDS retrieve failed for a candidate: {}", e.toString());
            return Optional.empty();
        }
    }

    private void applyHeaders(org.springframework.http.HttpHeaders headers, CallContext context) {
        headers.set("Accept", "application/fhir+json");
        headers.set("X-Request-ID", context.newRequestId());
        headers.set("X-Correlation-ID", context.correlationId());
        if (context.urid() != null && !context.urid().isBlank()) {
            // The role the reviewer selected, not any role they hold (FR-AUTH-05).
            headers.set("NHSD-Session-URID", context.urid());
        }
        if (context.organisation() != null && !context.organisation().isBlank()) {
            headers.set("NHSD-End-User-Organisation-ODS", context.organisation());
        }
        if (context.bearerToken() != null && !context.bearerToken().isBlank()) {
            headers.setBearerAuth(context.bearerToken());
        }
    }

    /** Turns a response into an outcome. The whole point is that these stay distinguishable. */
    private PdsSearchResult read(HttpStatusCode status, String body, long elapsedMs) {
        if (status.is2xxSuccessful()) {
            return PdsSearchResult.results(candidates(JSON.readTree(body)), elapsedMs);
        }
        if (mentionsTooManyMatches(body)) {
            return PdsSearchResult.tooMany(elapsedMs);
        }
        return PdsSearchResult.error(
                "PDS answered " + status.value() + ": " + diagnostics(body), elapsedMs);
    }

    private static List<PdsCandidate> candidates(JsonNode bundle) {
        List<PdsCandidate> candidates = new ArrayList<>();
        JsonNode entries = bundle == null ? null : bundle.get("entry");
        if (entries == null || !entries.isArray()) {
            return candidates;
        }
        for (JsonNode entry : entries) {
            JsonNode resource = entry.get("resource");
            candidates.add(PdsCandidate.fromEntry(entry, resource == null ? null : resource.toString()));
        }
        // PDS returns them in descending score order and the screen keeps that order, so a
        // reviewer reads the strongest candidate first (FR-PDS-06).
        return candidates;
    }

    /**
     * PDS reports the over-cap condition as an OperationOutcome rather than an empty bundle.
     * Matched on the body because the status alone does not distinguish it from other refusals.
     */
    private static boolean mentionsTooManyMatches(String body) {
        return body != null && body.contains(TOO_MANY_MATCHES);
    }

    /** The human-readable part of an OperationOutcome, where there is one. */
    private static String diagnostics(String body) {
        if (body == null || body.isBlank()) {
            return "no detail";
        }
        try {
            JsonNode outcome = JSON.readTree(body);
            JsonNode issues = outcome.get("issue");
            if (issues != null && issues.isArray() && !issues.isEmpty()) {
                JsonNode first = issues.get(0);
                JsonNode diagnostics = first.get("diagnostics");
                if (diagnostics != null && diagnostics.isValueNode()) {
                    return diagnostics.asText();
                }
                JsonNode code = first.get("code");
                if (code != null && code.isValueNode()) {
                    return code.asText();
                }
            }
        } catch (RuntimeException ignored) {
            // A body that is not JSON is still worth not echoing wholesale onto a clinical
            // screen; the status code above already says something useful.
        }
        return "no detail";
    }

    private static String body(org.springframework.http.client.ClientHttpResponse response) {
        try (var in = response.getBody()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
