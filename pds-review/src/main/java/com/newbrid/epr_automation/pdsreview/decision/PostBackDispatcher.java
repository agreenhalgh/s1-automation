package com.newbrid.epr_automation.pdsreview.decision;

import com.newbrid.epr_automation.pdsreview.config.PdsReviewProperties;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItem;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItemRepository;
import com.newbrid.epr_automation.pdsreview.domain.SearchRecord;
import com.newbrid.epr_automation.pdsreview.domain.SearchRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Delivers decisions to the gateway, and keeps trying until it succeeds or somebody is told.
 *
 * <p>This is the second half of the transactional outbox. {@link DecisionWriter} commits the
 * decision with {@code postBackState = PENDING}; this finds those rows and sends them. Nothing is
 * ever sent that was not first written down, and nothing written down is ever forgotten
 * (FR-DEC-07, NFR-REL-01).
 *
 * <p>Two things travel with every decision and both matter:
 * <ul>
 *   <li><b>The presentation, verbatim.</b> Exactly the JSON that arrived, with the matched
 *       patient added for a MATCHED decision and nothing else altered (FR-DEC-04).</li>
 *   <li><b>An idempotency key</b> — the review item key, in a header and in the body. A retried
 *       delivery must not register the same patient twice, and a duplicate registration in an
 *       EPR is a clinical safety incident rather than a data-quality one (FR-DEC-06, R4).</li>
 * </ul>
 *
 * <p>The reason code carries its own class, so the gateway route branches on the
 * {@code ACCEPTED_}/{@code CLOSED_} prefix rather than on an enumeration both sides must agree
 * about. {@code registersPatient} says the same thing in one boolean, so a route that has never
 * heard of a new reason code still knows whether it was meant to dispatch anything (FR-DEC-05a).
 */
@Component
public class PostBackDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PostBackDispatcher.class);
    private static final JsonMapper JSON = new JsonMapper();

    /** How many to attempt per tick. Bounded so a large backlog cannot monopolise the thread. */
    private static final int BATCH = 20;

    private final ReviewItemRepository items;
    private final SearchRecordRepository searches;
    private final PostBackWriter writer;
    private final PdsReviewProperties.PostBack config;
    private final RestClient http;
    private final Clock clock;
    private final boolean configured;

    public PostBackDispatcher(ReviewItemRepository items, SearchRecordRepository searches,
                              PostBackWriter writer, PdsReviewProperties properties, Clock clock) {
        this.items = items;
        this.searches = searches;
        this.writer = writer;
        this.config = properties.postBack();
        this.clock = clock;
        this.configured = config.url() != null && !config.url().isBlank();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) config.connectTimeout().toMillis());
        factory.setReadTimeout((int) config.readTimeout().toMillis());
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Scheduled(fixedDelayString = "${systmone.pds-review.post-back.retry-interval:30s}")
    public void dispatchPending() {
        if (!configured) {
            return;
        }
        Instant now = Instant.now(clock);
        List<ReviewItem> pending = items.awaitingPostBack(now, PageRequest.of(0, BATCH));
        for (ReviewItem item : pending) {
            deliver(item);
        }
    }

    /** One attempt. Never throws: a failure is a recorded attempt, not a lost decision. */
    void deliver(ReviewItem item) {
        String payload = payloadFor(item).toString();
        try {
            String body = http.post()
                    .uri(config.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        headers.set("X-Gateway-Token", config.token());
                        // Both places on purpose: a route may de-duplicate at the edge before it
                        // ever parses a body.
                        headers.set("X-Idempotency-Key", item.getItemKey());
                        if (item.getCorrelationId() != null) {
                            headers.set("X-Correlation-Id", item.getCorrelationId());
                        }
                    })
                    .body(payload)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new IllegalStateException(
                                    "the gateway answered " + response.getStatusCode().value());
                        }
                        return read(response);
                    }, false);

            writer.acknowledged(item.getId(), mrnFrom(body));
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Decision for {} was not delivered: {}", item.getItemKey(), reason);
            writer.failed(item.getId(), reason,
                    Instant.now(clock).plus(config.retryInterval()), config.maxAttempts());
        }
    }

    /**
     * The decision, the evidence behind it, and the presentation it concerns (FR-DEC-05).
     *
     * <p>The searches are included because the order the reviewer took through them is no longer
     * fixed (FR-REV-09), so what was actually asked of PDS is part of what the decision means —
     * particularly for an accept-unmatched decision, which has to say what PDS was asked
     * (FR-DEC-01d).
     */
    ObjectNode payloadFor(ReviewItem item) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("reviewItemKey", item.getItemKey());
        payload.put("idempotencyKey", item.getItemKey());
        payload.put("correlationId", item.getCorrelationId());
        payload.put("decision", item.getDecision().name());
        payload.put("decisionClass", item.getDecision().decisionClass().name());
        payload.put("registersPatient", item.getDecision().registers());
        payload.put("decidedAt", String.valueOf(item.getDecidedAt()));
        payload.put("note", item.getDecisionNote());
        payload.put("existingRecordId", item.getExistingRecordId());

        ObjectNode reviewer = payload.putObject("reviewer");
        reviewer.put("userId", item.getDecidedBy());
        reviewer.put("roleId", item.getDecidedByRole());

        if (item.getMatchedNhsNumber() != null) {
            payload.put("nhsNumber", item.getMatchedNhsNumber());
        }
        if (item.getMatchedPatientJson() != null) {
            payload.set("matchedPatient", JSON.readTree(item.getMatchedPatientJson()));
        }

        ArrayNode evidence = payload.putArray("searches");
        for (SearchRecord search : searches.findByItemIdOrderByRunAtAsc(item.getId())) {
            ObjectNode entry = evidence.addObject();
            entry.put("kind", search.getKind().name());
            entry.put("parameters", search.getParameters());
            entry.put("outcome", search.getOutcome().name());
            entry.put("resultCount", search.getResultCount());
            entry.put("runAt", String.valueOf(search.getRunAt()));
            entry.put("runBy", search.getRunBy());
        }

        // Last, and unaltered: the presentation as OPTO Health sent it (FR-INT-04, FR-DEC-04).
        payload.set("presentation", JSON.readTree(item.getPresentationJson()));
        return payload;
    }

    /** The MRN the control plane gives the registered patient, where it returns one. */
    private static String mrnFrom(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode answer = JSON.readTree(body);
            JsonNode mrn = answer.get("mrn");
            return mrn == null || !mrn.isValueNode() ? null : mrn.asText();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String read(org.springframework.http.client.ClientHttpResponse response) {
        try (var in = response.getBody()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
