package com.newbrid.epr_automation.gateway.controlplane;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * The control plane's REST contract, as this gateway needs to see it — hand-written rather than
 * shared as a library on purpose. The gateway integrates over HTTP like any other client, and a
 * compile-time dependency on the control plane's DTOs would turn every internal rename there
 * into a build break here, which is exactly the coupling a gateway exists to prevent.
 *
 * <p>Unknown properties are <b>ignored</b> on the way in, the opposite of the inbound
 * {@link com.newbrid.epr_automation.gateway.api.RunSubmission} policy. A run snapshot carries a
 * great deal this gateway has no interest in — step history, screenshot references, stack traces
 * — and the control plane must stay free to add more without breaking the gateway. Strictness
 * belongs at the edge where a caller might be making a mistake, not on a response from a service
 * that is entitled to evolve.
 */
public final class ControlPlaneWire {

    private ControlPlaneWire() {}

    /** POST /api/runs. */
    public record DispatchRequest(String botId, Map<String, String> input, String capabilityTag) {}

    /** The response to that, which carries nothing but the id of the run just created. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DispatchResponse(String runId) {}

    /** GET /api/runs/{id} — a run plus its step history, of which only the run is read. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunLookup(RunView run) {}

    /**
     * As much of a run record as the gateway reports on.
     *
     * @param status      one of the control plane's RunStatus values, by name
     * @param outputsJson the outputs map, still encoded as the JSON string the control plane
     *                    stores it as. Decoded only when there is a successful outcome to
     *                    report, so a malformed value cannot cost a caller their answer
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunView(String id, String botId, String status, String workerId,
                          String errorType, String errorMessage, String outputsJson,
                          String createdAt, String startedAt, String finishedAt) {}
}
