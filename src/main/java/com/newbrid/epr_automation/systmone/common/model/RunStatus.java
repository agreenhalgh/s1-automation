package com.newbrid.epr_automation.systmone.common.model;

/**
 * Lifecycle state of a bot run. Terminal states are recorded in PostgreSQL and are
 * never left implicit (NFR-REL-02: no run is silently lost).
 */
public enum RunStatus {

    /** Created in the control plane, not yet published to the queue. */
    PENDING,
    /** Published to the RabbitMQ work queue, awaiting a worker. */
    DISPATCHED,
    /** A worker has claimed the job and is executing it. */
    RUNNING,

    // ---- terminal states ----
    SUCCEEDED,
    FAILED,
    ABORTED,
    TIMED_OUT;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == ABORTED || this == TIMED_OUT;
    }
}
