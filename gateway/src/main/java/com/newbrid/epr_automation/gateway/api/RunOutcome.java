package com.newbrid.epr_automation.gateway.api;

import java.util.Set;

/**
 * What this gateway is prepared to answer with. The first four are the control plane's own
 * terminal run statuses passed straight through; the last two are the gateway's, for the cases
 * where it never got a definitive answer to pass on.
 *
 * <p>The distinction between {@link #TIMED_OUT} and {@link #GATEWAY_TIMEOUT} is the one that
 * matters operationally and is the reason this is an enum rather than a string. TIMED_OUT is a
 * <em>finished</em> run: the engine hit its own deadline, gave up, and recorded that. Nothing is
 * still happening. GATEWAY_TIMEOUT is the opposite — the run is very likely still executing
 * against a live patient record, and only this gateway has stopped waiting. Reporting the second
 * as the first would tell a caller that a clinical action had definitively ended when it had not.
 */
public enum RunOutcome {

    SUCCEEDED(true),
    FAILED(true),
    ABORTED(true),
    /** The engine hit its own global timeout and recorded a terminal state (FR-ENG-08). */
    TIMED_OUT(true),

    /**
     * The gateway stopped waiting. The run was still non-terminal at that point and may still be
     * running: not a verdict on the run, a statement about this request.
     */
    GATEWAY_TIMEOUT(false),

    /** The run was never created — the control plane refused it or could not be reached. */
    NOT_DISPATCHED(false);

    /** Statuses the control plane treats as terminal, by name, for reading a run snapshot. */
    private static final Set<String> TERMINAL_STATUSES =
            Set.of("SUCCEEDED", "FAILED", "ABORTED", "TIMED_OUT");

    private final boolean definitive;

    RunOutcome(boolean definitive) {
        this.definitive = definitive;
    }

    /**
     * Whether the run itself reached a settled state. False means the outcome describes this
     * request rather than the run, and that something may still be in flight.
     */
    public boolean definitive() {
        return definitive;
    }

    /** Whether a status reported by the control plane means the run has stopped. */
    public static boolean isTerminalStatus(String status) {
        return status != null && TERMINAL_STATUSES.contains(status);
    }

    /**
     * The outcome for a control-plane status. Only ever called with a status
     * {@link #isTerminalStatus} accepted, so an unrecognised one is a contract change between
     * the two services rather than a runtime condition to paper over.
     */
    public static RunOutcome ofStatus(String status) {
        return valueOf(status);
    }
}
