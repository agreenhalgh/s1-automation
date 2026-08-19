package com.newbrid.epr_automation.systmone.worker;

import com.newbrid.epr_automation.systmone.common.dto.Heartbeat;
import com.newbrid.epr_automation.systmone.common.dto.StatusReport;
import com.newbrid.epr_automation.systmone.common.dto.StepReport;
import com.newbrid.epr_automation.systmone.common.dto.WorkerRegistration;
import com.newbrid.epr_automation.systmone.controlplane.security.WorkerTokenFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client a worker uses to POST outcomes back to the control plane (FR-WRK-03) and to
 * fetch template images. Every call carries the worker token (FR-WRK-07) and worker id.
 */
public class ControlPlaneClient {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneClient.class);

    private final RestClient http;
    private final String workerId;

    public ControlPlaneClient(String baseUrl, String token, String workerId) {
        this.workerId = workerId;
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(WorkerTokenFilter.HEADER, token)
                .defaultHeader("X-Worker-Id", workerId)
                .build();
    }

    public void register(WorkerRegistration registration) {
        http.post().uri("/api/workers/register").body(registration).retrieve().toBodilessEntity();
    }

    public void postStep(String runId, StepReport report) {
        http.post().uri("/api/runs/{id}/steps", runId).body(report).retrieve().toBodilessEntity();
    }

    /** Post a status transition. Throws on failure so the caller can retry / dead-letter (FR-WRK-04/05). */
    public void postStatus(String runId, StatusReport report) {
        http.post().uri("/api/runs/{id}/status", runId).body(report).retrieve().toBodilessEntity();
    }

    public void heartbeat(String runId, Heartbeat hb) {
        http.post().uri("/api/runs/{id}/heartbeat", runId).body(hb).retrieve().toBodilessEntity();
    }

    /** @return template image bytes, or {@code null} if unavailable. */
    public byte[] fetchTemplate(String name) {
        try {
            return http.get().uri("/api/templates/{name}/image", name).retrieve().body(byte[].class);
        } catch (Exception e) {
            log.warn("Failed to fetch template '{}': {}", name, e.toString());
            return null;
        }
    }

    public String workerId() {
        return workerId;
    }
}
