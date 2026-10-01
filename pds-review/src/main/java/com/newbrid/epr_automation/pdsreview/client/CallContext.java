package com.newbrid.epr_automation.pdsreview.client;

import java.util.UUID;

/**
 * Who a national-API call is being made on behalf of.
 *
 * <p>PDS is user-restricted: every request carries the reviewer's access token and the URID of
 * the role they selected, as {@code NHSD-Session-URID} (FR-AUTH-05). A search made under one role
 * must not be attributable to another, which is why the role travels with the call rather than
 * being read from a session somewhere inside the client.
 *
 * <p>ODS is unauthenticated (FR-ODS-10), so it uses only the correlation id — and nothing
 * patient-identifying ever reaches it (FR-ODS-11).
 *
 * @param userId        the reviewer, for logging and the audit trail
 * @param urid          the selected role's unique id
 * @param organisation  the reviewer's organisation ODS code
 * @param bearerToken   the reviewer's PDS access token, or null where none is configured
 * @param correlationId shared with this item's rows in the gateway's trail (FR-AUD-04)
 */
public record CallContext(String userId, String urid, String organisation,
                          String bearerToken, String correlationId) {

    public static CallContext of(String userId, String urid, String organisation,
                                 String bearerToken, String correlationId) {
        return new CallContext(userId, urid, organisation, bearerToken,
                correlationId == null ? UUID.randomUUID().toString() : correlationId);
    }

    /** A fresh id per request, as NHS Digital's platform conventions expect. */
    public String newRequestId() {
        return UUID.randomUUID().toString();
    }
}
