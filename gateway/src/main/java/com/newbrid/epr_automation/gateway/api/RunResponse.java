package com.newbrid.epr_automation.gateway.api;

import java.util.Map;

/**
 * The answer a caller waited for. Returned only once the run has definitively succeeded, failed
 * or timed out — or once this gateway has given up waiting, which is reported as such rather
 * than dressed up as an outcome.
 *
 * <p>Timestamps are strings in ISO-8601 UTC rather than instants: they are passed through from
 * the control plane, whose database runs in UTC, and re-parsing them here only to re-render them
 * would create a second place for a zone to be applied by accident.
 *
 * @param definitive whether {@code outcome} is a verdict on the run. False means the run may
 *                   still be executing and this response is about the request, not the work
 * @param outputs    whatever the bot produced, as the control plane recorded it
 * @param error      populated for a failed run; the type and message, not a stack trace, which
 *                   belongs in the run record rather than in an integration response
 */
public record RunResponse(
        String requestId,
        String correlationId,
        String runId,
        RunOutcome outcome,
        boolean definitive,
        String startedAt,
        String finishedAt,
        long waitedMs,
        Map<String, String> outputs,
        ErrorDetail error,
        String message) {

    public record ErrorDetail(String type, String message) {}

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Assembled across several route steps — the correlation id at the door, the run id after
     * dispatch, the outcome after waiting — so it is built rather than constructed in one go.
     */
    public static final class Builder {
        private String requestId;
        private String correlationId;
        private String runId;
        private RunOutcome outcome;
        private String startedAt;
        private String finishedAt;
        private long waitedMs;
        private Map<String, String> outputs;
        private ErrorDetail error;
        private String message;

        public Builder requestId(String v) { this.requestId = v; return this; }
        public Builder correlationId(String v) { this.correlationId = v; return this; }
        public Builder runId(String v) { this.runId = v; return this; }
        public Builder outcome(RunOutcome v) { this.outcome = v; return this; }
        public Builder startedAt(String v) { this.startedAt = v; return this; }
        public Builder finishedAt(String v) { this.finishedAt = v; return this; }
        public Builder waitedMs(long v) { this.waitedMs = v; return this; }
        public Builder outputs(Map<String, String> v) { this.outputs = v; return this; }
        public Builder error(ErrorDetail v) { this.error = v; return this; }
        public Builder message(String v) { this.message = v; return this; }

        public RunResponse build() {
            return new RunResponse(requestId, correlationId, runId, outcome,
                    outcome != null && outcome.definitive(), startedAt, finishedAt, waitedMs,
                    outputs, error, message);
        }
    }
}
