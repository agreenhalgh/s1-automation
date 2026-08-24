package com.newbrid.epr_automation.gateway.route;

/**
 * Names of the exchange properties the pipeline carries its own state in, and of the HTTP headers
 * it reads and writes.
 *
 * <p>Properties, not headers, for everything internal. A header on an exchange that then makes an
 * HTTP call gets <em>sent</em> as a header, so state kept there leaks the gateway's internals —
 * and worse, response headers from the control plane come back and are sent again on the next
 * call. Exchange properties never cross the wire, which makes them the right place for a
 * correlation id, a deadline and a run snapshot. The HTTP calls still clear headers before each
 * request; that is belt-and-braces on top of not putting anything there in the first place.
 */
public final class GatewayExchange {

    private GatewayExchange() {}

    // ---- exchange properties (internal, never sent) --------------------------------------

    /** The gateway's id for this request, present from the first step to the last. */
    public static final String CORRELATION_ID = "GwCorrelationId";

    /** The caller's own id, if they sent one. */
    public static final String CLIENT_REQUEST_ID = "GwClientRequestId";

    /** Where the request came from, for the audit trail. */
    public static final String SOURCE = "GwSource";

    /** {@code System.nanoTime()} at arrival — the basis for every elapsed time reported. */
    public static final String STARTED_NANOS = "GwStartedNanos";

    /** The parsed submission, kept so later steps do not re-read a consumed body. */
    public static final String SUBMISSION = "GwSubmission";

    /** {@code System.currentTimeMillis()} past which the gateway stops waiting. */
    public static final String DEADLINE_MS = "GwDeadlineMs";

    /** The control plane's run id, once dispatch has returned one. */
    public static final String RUN_ID = "GwRunId";

    /** The run status from the most recent poll. */
    public static final String RUN_STATUS = "GwRunStatus";

    /** The most recent run snapshot, which becomes the response. */
    public static final String RUN_VIEW = "GwRunView";

    // ---- HTTP headers --------------------------------------------------------------------

    /** Presented by a caller to this gateway. */
    public static final String GATEWAY_TOKEN_HEADER = "X-Gateway-Token";

    /** Presented by this gateway to the control plane (FR-WRK-07). */
    public static final String WORKER_TOKEN_HEADER = "X-Worker-Token";

    /**
     * Echoed to the caller on every response, including errors, so a correlation id never has to
     * be dug out of a body that might be a problem document.
     */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
}
