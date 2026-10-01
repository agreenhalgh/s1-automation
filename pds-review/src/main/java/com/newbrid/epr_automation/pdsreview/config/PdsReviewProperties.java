package com.newbrid.epr_automation.pdsreview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Everything in {@code systmone.pds-review}, bound once so no class reads a property key by hand.
 *
 * <p>The values that decide clinical behaviour live here rather than in code on purpose. The
 * worklist ordering (FR-WL-05), the activity codes that grant access (FR-AUTH-07) and the ODS
 * role code for a GP practice (FR-ODS-04) are all decisions somebody outside this codebase owns
 * — information governance, the Registration Authority, NHS England — and each of them should be
 * answerable by editing configuration under review rather than by cutting a release.
 *
 * @param auth     which national RBAC activity code grants what
 * @param intake   the endpoint the gateway POSTs unmatched presentations to
 * @param queue    how the worklist is ordered
 * @param review   how long a reviewer holds an item
 * @param pds      the Personal Demographics Service client
 * @param ods      the Organisation Data Service client
 * @param postBack where decisions are sent, and how hard we try
 */
@ConfigurationProperties(prefix = "systmone.pds-review")
public record PdsReviewProperties(
        Auth auth,
        Intake intake,
        Queue queue,
        Review review,
        Pds pds,
        Ods ods,
        PostBack postBack) {

    /**
     * The activity codes on a reviewer's selected role that grant capability here.
     *
     * @param review activity granting REVIEWER — work the queue
     * @param audit  activity granting AUDITOR — read the trail
     */
    public record Activity(
            @DefaultValue("B0830") String review,
            @DefaultValue("B0011") String audit) {}

    /**
     * @param activity   see {@link Activity}
     * @param claim      the CIS2 ID-token claim carrying the user's national RBAC roles. A
     *                   property because the exact claim name is unconfirmed (Open Question 1),
     *                   and finding out should not mean a code change
     * @param requireCis2 whether a CIS2 login is the only way in. False only for local
     *                   development, and refused outright in the {@code prod} profile
     */
    public record Auth(
            Activity activity,
            @DefaultValue("nhsid_nrbac_roles") String claim,
            @DefaultValue("true") boolean requireCis2) {}

    /**
     * @param path  where the gateway POSTs
     * @param token the shared credential on that call. Not a user identity — no user is involved
     *              in intake, which is why this route exposes no read path (NFR-SEC-01a)
     */
    public record Intake(
            @DefaultValue("/internal/v1/presentations") String path,
            @DefaultValue("changeit") String token) {}

    /**
     * @param triageScoreOrder most urgent first. Position in this list is the rank
     * @param unscoredBand     where an item with no triage score sorts — {@code top} by default,
     *                         because a missing score is an absence of evidence and not evidence
     *                         of low urgency (FR-WL-04)
     * @param waitFrom         {@code triage-end} measures the wait from when the patient finished
     *                         triage; {@code receipt} from when we were told (FR-WL-03)
     * @param refreshInterval  how often the open worklist re-fetches itself (FR-WL-09, FR-WL-11)
     * @param refreshQuietAfter how long the reviewer can be idle before refreshing stops. Shorter
     *                         than the session timeout, deliberately: polling is request
     *                         activity, so a page left open would otherwise hold a session — and
     *                         a patient record — open indefinitely (FR-WL-11b, FR-AUTH-08)
     */
    public record Queue(
            @DefaultValue({"P0", "P1", "P2", "P3", "P4", "P5"}) List<String> triageScoreOrder,
            @DefaultValue("top") String unscoredBand,
            @DefaultValue("triage-end") String waitFrom,
            @DefaultValue("30s") Duration refreshInterval,
            @DefaultValue("5m") Duration refreshQuietAfter) {

        public boolean unscoredAtTop() {
            return !"bottom".equalsIgnoreCase(unscoredBand);
        }

        public boolean waitFromTriageEnd() {
            return !"receipt".equalsIgnoreCase(waitFrom);
        }
    }

    /**
     * @param lockDuration     how long one reviewer holds an item before it returns to the queue
     * @param lockSweepInterval how often expired locks are reclaimed
     */
    public record Review(
            @DefaultValue("20m") Duration lockDuration,
            @DefaultValue("1m") Duration lockSweepInterval) {}

    /**
     * @param maxResults        PDS caps a search at 50 and answers TOO_MANY_MATCHES beyond it
     * @param requestsPerSecond client-side throttle, so this application cannot exhaust the
     *                          organisation's PDS quota and deny other services access
     */
    public record Pds(
            String baseUrl,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout,
            @DefaultValue("50") int maxResults,
            @DefaultValue("5") int requestsPerSecond) {}

    /**
     * @param gpPracticeRoleCode the ODS role a GP practice holds — Prescribing Cost Centre.
     *                           Configuration because the binding decision is the role, and the
     *                           code is only how ODS spells it (FR-ODS-04)
     */
    public record Ods(
            String baseUrl,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout,
            @DefaultValue("RO177") String gpPracticeRoleCode,
            @DefaultValue("24h") Duration practiceCacheTtl,
            @DefaultValue("20") int maxResults) {}

    /**
     * @param maxAttempts after which a decision is reported as failed rather than retried
     *                    forever. It is never discarded: FR-DEC-08 requires somebody to be told
     */
    public record PostBack(
            String url,
            @DefaultValue("changeit") String token,
            @DefaultValue("30s") Duration retryInterval,
            @DefaultValue("20") int maxAttempts,
            @DefaultValue("10s") Duration connectTimeout,
            @DefaultValue("30s") Duration readTimeout) {}
}
