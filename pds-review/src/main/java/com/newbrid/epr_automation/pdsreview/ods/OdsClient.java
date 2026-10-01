package com.newbrid.epr_automation.pdsreview.ods;

import com.newbrid.epr_automation.pdsreview.client.CallContext;
import com.newbrid.epr_automation.pdsreview.client.CallThrottle;
import com.newbrid.epr_automation.pdsreview.config.PdsReviewProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds GP practices in the Organisation Data Service, so a reviewer never has to know an ODS
 * code (FR-ODS-02).
 *
 * <p><b>Unauthenticated</b> (FR-ODS-10). There is no credential to hold and none to leak, but an
 * API that asks nothing of us also promises nothing to us — hence a timeout budget, a throttle
 * and a cache, exactly as if it did.
 *
 * <p><b>Nothing patient-identifying crosses this boundary</b> (FR-ODS-11). The query type below
 * carries a practice name, a town and a postcode <em>fragment</em>; there is no field for a
 * patient's name, date of birth or NHS number, so there is no path by which one is sent. Where a
 * reviewer looks for practices near the patient's home, the caller passes the outward code alone
 * — which is all ODS's leading-match semantics need.
 */
@Component
public class OdsClient {

    private static final Logger log = LoggerFactory.getLogger(OdsClient.class);
    private static final JsonMapper JSON = new JsonMapper();

    private final RestClient http;
    private final CallThrottle throttle;
    private final PdsReviewProperties.Ods config;
    private final boolean configured;
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    public OdsClient(PdsReviewProperties properties) {
        this.config = properties.ods();
        this.configured = config.baseUrl() != null && !config.baseUrl().isBlank();
        // Modest by default: this is reference data behind a cache, not a per-patient lookup.
        this.throttle = new CallThrottle(10);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) config.connectTimeout().toMillis());
        factory.setReadTimeout((int) config.readTimeout().toMillis());
        this.http = RestClient.builder().requestFactory(factory).build();

        if (!configured) {
            log.warn("No ODS base URL is configured. Practice lookup will report an error; the "
                    + "reviewer can still work the non-fuzzy and fuzzy routes (NFR-REL-03).");
        }
    }

    /**
     * What may be asked of ODS. Deliberately narrow — see the class comment.
     *
     * @param name         all or the start of the practice name
     * @param anyPartOfName whether to match anywhere in the name ({@code name:contains}) rather
     *                     than from the start
     * @param postcodeFragment the outward code, or the start of a postcode. Never a patient's
     *                     full postcode
     * @param includeInactive whether closed and merged practices are included (FR-ODS-05)
     */
    public record PracticeQuery(String name, boolean anyPartOfName, String postcodeFragment,
                                String town, boolean includeInactive, int offset) {

        public boolean empty() {
            return blank(name) && blank(postcodeFragment) && blank(town);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    /**
     * @param problem null when the search ran; a reason when it did not. An outage here is shown
     *                as an outage, never as "no practices found" — the same rule as PDS
     */
    public record PracticeResult(List<GpPractice> practices, String query, String problem,
                                 long elapsedMs, boolean fromCache) {

        public boolean failed() {
            return problem != null;
        }
    }

    public boolean configured() {
        return configured;
    }

    /** The role a GP practice holds — Prescribing Cost Centre by default (FR-ODS-04). */
    public String gpPracticeRoleCode() {
        return config.gpPracticeRoleCode();
    }

    public PracticeResult findPractices(PracticeQuery query, CallContext context) {
        if (query.empty()) {
            return new PracticeResult(List.of(), "", "Enter a practice name, town or postcode",
                    0L, false);
        }
        if (!configured) {
            return new PracticeResult(List.of(), "", "ODS is not configured in this environment",
                    0L, false);
        }

        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(config.baseUrl())
                .path("/Organization");

        if (query.name() != null && !query.name().isBlank()) {
            uri.queryParam(query.anyPartOfName() ? "name:contains" : "name", query.name().trim());
        }
        if (query.postcodeFragment() != null && !query.postcodeFragment().isBlank()) {
            // ODS spells it -postalcode; PDS spells it -postcode. Each client owns its own
            // parameter names precisely so this pair cannot be confused (NFR-MNT-01).
            uri.queryParam("address-postalcode", query.postcodeFragment().trim());
        }
        if (query.town() != null && !query.town().isBlank()) {
            uri.queryParam("address-city", query.town().trim());
        }
        // activeRoleCode rather than roleCode: a practice whose role has been retired should not
        // surface as a current option (FR-ODS-04).
        uri.queryParam("activeRoleCode", config.gpPracticeRoleCode());
        if (!query.includeInactive()) {
            uri.queryParam("active", "true");
        }
        uri.queryParam("_count", config.maxResults());
        if (query.offset() > 0) {
            uri.queryParam("_offset", query.offset());
        }

        String url = uri.build().toUriString();
        String canonical = url.substring(url.indexOf("/Organization"));

        CachedResult cached = cache.get(url);
        if (cached != null && !cached.olderThan(config.practiceCacheTtl())) {
            return new PracticeResult(cached.practices(), canonical, null, 0L, true);
        }

        long started = System.nanoTime();
        throttle.acquire();
        try {
            List<GpPractice> practices = http.get()
                    .uri(url)
                    .headers(headers -> {
                        headers.set("Accept", "application/fhir+json");
                        headers.set("X-Correlation-ID", context.correlationId());
                    })
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new IllegalStateException(
                                    "ODS answered " + response.getStatusCode().value());
                        }
                        return read(JSON.readTree(body(response)));
                    }, false);

            cache.put(url, new CachedResult(practices, Instant.now()));
            return new PracticeResult(practices, canonical, null, elapsedMs(started), false);
        } catch (Exception e) {
            log.warn("ODS practice search failed: {}", e.toString());
            return new PracticeResult(List.of(), canonical,
                    "The organisation directory could not be reached: " + reason(e),
                    elapsedMs(started), false);
        }
    }

    private static List<GpPractice> read(JsonNode bundle) {
        List<GpPractice> practices = new ArrayList<>();
        JsonNode entries = bundle == null ? null : bundle.get("entry");
        if (entries == null || !entries.isArray()) {
            return practices;
        }
        for (JsonNode entry : entries) {
            JsonNode resource = entry.get("resource");
            if (resource != null) {
                practices.add(GpPractice.fromResource(resource));
            }
        }
        return List.copyOf(practices);
    }

    private record CachedResult(List<GpPractice> practices, Instant at) {
        boolean olderThan(Duration ttl) {
            return at.plus(ttl).isBefore(Instant.now());
        }
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
