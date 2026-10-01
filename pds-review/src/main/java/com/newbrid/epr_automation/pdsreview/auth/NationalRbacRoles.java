package com.newbrid.epr_automation.pdsreview.auth;

import org.springframework.security.core.Authentication;

import java.util.List;

/**
 * Where a signed-in person's national RBAC roles come from.
 *
 * <p>An interface with one real implementation, because of Open Question 1: the exact CIS2
 * ID-token claim carrying the roles, and its shape, are unconfirmed. Isolating the reading of it
 * means answering that question changes one class and its tests, not the security configuration,
 * the controllers and the audit trail.
 *
 * <p>It is also what lets the application run locally without a CIS2 registration, through a
 * development implementation that is refused outright in production.
 */
public interface NationalRbacRoles {

    /** Every role the person holds. The selection screen shows these (FR-AUTH-04). */
    List<NationalRbacRole> rolesFor(Authentication authentication);

    /** A stable identifier for the person, used as the actor on every audit entry. */
    String userId(Authentication authentication);

    /** Their name, for the page header. Never used as an identity. */
    String displayName(Authentication authentication);

    /** Their PDS access token, where the authentication carries one. */
    String bearerToken(Authentication authentication);
}
