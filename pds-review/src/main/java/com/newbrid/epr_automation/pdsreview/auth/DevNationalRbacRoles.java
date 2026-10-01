package com.newbrid.epr_automation.pdsreview.auth;

import com.newbrid.epr_automation.pdsreview.config.PdsReviewProperties;
import org.springframework.security.core.Authentication;

import java.util.List;

/**
 * Stand-in roles for local development, when no CIS2 client is registered.
 *
 * <p>It exists because the alternative is worse. Without it nobody can open the application until
 * a national identity provider has been configured, and the first time anyone exercises the
 * review screens would be against live patient data with a live PDS. NFR-SEC-07 asks for the
 * opposite: development and training on synthetic data, with no route to a real record.
 *
 * <p>The guard rails are in {@code SecurityConfig}, not here: this bean is created only when the
 * {@code prod} profile is absent <em>and</em> {@code systmone.pds-review.auth.require-cis2} has
 * been turned off, and the application refuses to start if that combination is asked for in
 * production. There is no configuration in which this and a live PDS base URL coexist.
 *
 * <p>The three users mirror the three real cases: somebody who can only review, somebody who can
 * only audit, and somebody holding both activities — who must still exercise one at a time
 * (FR-AUTH-07a), which is the case most worth being able to click through.
 */
public class DevNationalRbacRoles implements NationalRbacRoles {

    private final PdsReviewProperties properties;

    public DevNationalRbacRoles(PdsReviewProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<NationalRbacRole> rolesFor(Authentication authentication) {
        if (authentication == null) {
            return List.of();
        }
        String reviewActivity = properties.auth().activity().review();
        String auditActivity = properties.auth().activity().audit();

        return switch (authentication.getName()) {
            case "reviewer" -> List.of(reviewerRole(reviewActivity));
            case "auditor" -> List.of(auditorRole(auditActivity));
            case "both" -> List.of(reviewerRole(reviewActivity), auditorRole(auditActivity));
            // A signed-in person with no roles is a real situation — somebody whose Registration
            // Authority has not granted them anything yet — and worth being able to see.
            default -> List.of(new NationalRbacRole("555000000000", "R0001",
                    "No activities granted", "X26", "Development", List.of()));
        };
    }

    @Override
    public String userId(Authentication authentication) {
        return authentication == null ? "unknown" : "dev:" + authentication.getName();
    }

    @Override
    public String displayName(Authentication authentication) {
        return authentication == null ? "" : authentication.getName();
    }

    /** No token: there is no live PDS to present one to in this mode. */
    @Override
    public String bearerToken(Authentication authentication) {
        return null;
    }

    private static NationalRbacRole reviewerRole(String reviewActivity) {
        return new NationalRbacRole("555021935107", "R8000", "Clinical Practitioner Access Role",
                "X26", "Development Trust", List.of(reviewActivity, "B0001"));
    }

    private static NationalRbacRole auditorRole(String auditActivity) {
        return new NationalRbacRole("555021935108", "R8015", "Information Governance Officer",
                "X26", "Development Trust", List.of(auditActivity));
    }
}
