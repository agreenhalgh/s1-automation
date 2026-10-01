package com.newbrid.epr_automation.pdsreview.auth;

import java.util.List;
import java.util.Locale;

/**
 * One of the national RBAC roles a reviewer holds, as CIS2 reports it.
 *
 * <p>A person may hold several. Capability here follows the role they <em>selected</em>, never the
 * union of what they hold (FR-AUTH-07a) — which is what keeps the separation between reviewing
 * patients and reading the audit trail real for somebody who has both activities, rather than
 * only for people who happen to have one.
 *
 * @param urid         the unique role id. Sent to PDS as {@code NHSD-Session-URID} (FR-AUTH-05)
 * @param roleCode     the national RBAC role code
 * @param roleName     as it should be shown on the role-selection screen
 * @param organisation the ODS code of the organisation the role is held in
 * @param activities   the activity codes on this role — `B0830`, `B0011` and whatever else
 */
public record NationalRbacRole(String urid, String roleCode, String roleName,
                               String organisation, String organisationName,
                               List<String> activities) {

    public NationalRbacRole {
        activities = activities == null ? List.of() : List.copyOf(activities);
    }

    /** Whether this role carries an activity code, compared case-insensitively. */
    public boolean has(String activity) {
        if (activity == null || activity.isBlank()) {
            return false;
        }
        String wanted = activity.trim().toUpperCase(Locale.UK);
        return activities.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(held -> held.trim().toUpperCase(Locale.UK).equals(wanted));
    }

    /** What the role-selection screen shows: the role, and where it is held. */
    public String display() {
        String where = organisationName != null && !organisationName.isBlank()
                ? organisationName
                : organisation;
        return where == null || where.isBlank() ? roleName : roleName + " — " + where;
    }
}
