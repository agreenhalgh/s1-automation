package com.newbrid.epr_automation.systmone.controlplane.service;

import java.time.Instant;

/**
 * What an auditor is asking of the trail (NFR-SEC-05). Every field is optional and an absent
 * one is not a filter, so the empty query means "everything, newest first".
 *
 * <p>Blank strings are normalised to null in the constructor because these arrive from an HTML
 * form, where a field that was never filled in submits as {@code ""} rather than being absent.
 * Left as-is, an empty text box would silently become {@code principal like '%%'} — harmless
 * here, but the same laxity applied to {@code action}, which matches exactly, would filter
 * every row out and read as "no audit entries exist".
 *
 * @param principal who acted — matched as a substring, since an auditor looking for one person
 *                  should not have to know whether the trail recorded them as a username, a
 *                  worker id or an SSO subject
 * @param action    what they did — matched exactly, because the values are a closed set the UI
 *                  offers as a dropdown
 * @param botId     which bot, as a substring
 * @param runId     which run, as a substring, so a run id abbreviated in a report still finds it
 * @param from      inclusive lower bound on the timestamp
 * @param to        exclusive upper bound, so two adjacent ranges neither overlap nor drop a row
 */
public record AuditQuery(String principal, String action, String botId, String runId,
                         Instant from, Instant to) {

    public AuditQuery {
        principal = blankToNull(principal);
        action = blankToNull(action);
        botId = blankToNull(botId);
        runId = blankToNull(runId);
    }

    /** The unfiltered trail. */
    public static AuditQuery all() {
        return new AuditQuery(null, null, null, null, null, null);
    }

    /** Whether anything is actually being filtered on — drives the "showing everything" note. */
    public boolean filtered() {
        return principal != null || action != null || botId != null || runId != null
                || from != null || to != null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
