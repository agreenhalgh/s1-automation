package com.newbrid.epr_automation.pdsreview.auth;

import com.newbrid.epr_automation.pdsreview.client.CallContext;

/**
 * Who is doing this, under which role.
 *
 * <p>Every audited event and every terminal decision is attributable to one of these
 * (FR-AUTH-05, FR-DEC-11). There is deliberately no way to build one representing a service
 * account or a shared login: the intake route, which genuinely has no user, does not use this
 * type at all.
 *
 * @param selectedRole the role the reviewer chose for this session — the single source of their
 *                     capability, and the URID sent to PDS
 * @param bearerToken  their PDS access token, where one is available
 */
public record ReviewerIdentity(String userId, String displayName,
                               NationalRbacRole selectedRole, String bearerToken) {

    /** Whether the selected role carries the activity that grants the queue (FR-AUTH-06). */
    public boolean canReview(String reviewActivity) {
        return selectedRole != null && selectedRole.has(reviewActivity);
    }

    /** Whether the selected role carries the activity that grants the audit trail. */
    public boolean canAudit(String auditActivity) {
        return selectedRole != null && selectedRole.has(auditActivity);
    }

    public String urid() {
        return selectedRole == null ? null : selectedRole.urid();
    }

    public String organisation() {
        return selectedRole == null ? null : selectedRole.organisation();
    }

    /** The context for a call to PDS or ODS on this reviewer's behalf. */
    public CallContext callContext(String correlationId) {
        return CallContext.of(userId, urid(), organisation(), bearerToken, correlationId);
    }
}
