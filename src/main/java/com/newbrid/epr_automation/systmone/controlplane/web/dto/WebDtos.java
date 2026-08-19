package com.newbrid.epr_automation.systmone.controlplane.web.dto;

import com.newbrid.epr_automation.systmone.controlplane.domain.RunEntity;
import com.newbrid.epr_automation.systmone.controlplane.domain.StepResultEntity;
import com.newbrid.epr_automation.systmone.controlplane.domain.TemplateEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request/response payloads for the control-plane REST API. */
public final class WebDtos {

    private WebDtos() {}

    /** POST /api/runs — dispatch a bot run (FR-CP-05). */
    public record DispatchRequest(String botId, Map<String, String> input, String capabilityTag) {}

    public record DispatchResponse(String runId) {}

    /** POST /api/templates — upload a captured partial screenshot (FR-TOOL-02, FR-SR-08). */
    public record TemplateUploadRequest(
            String name, String s1Version, Double threshold, String screenContext, String dataBase64) {}

    /**
     * GET /api/templates and /api/templates/{name} — a template's registry metadata
     * (FR-SR-08, FR-REG-01). The image itself stays out of the payload: {@code bytes} and
     * {@code checksum} describe it, and /api/templates/{name}/image serves it.
     */
    public record TemplateSummary(
            String id, String name, String s1Version, double threshold, String screenContext,
            String createdBy, String checksum, int bytes, Instant createdAt) {

        public static TemplateSummary of(TemplateEntity t) {
            return new TemplateSummary(t.getId(), t.getName(), t.getS1Version(), t.getThreshold(),
                    t.getScreenContext(), t.getCreatedBy(), t.getChecksum(),
                    t.getData() != null ? t.getData().length : 0, t.getCreatedAt());
        }
    }

    /** GET /api/runs/{id} — run plus its step history. */
    public record RunDetail(RunEntity run, List<StepResultEntity> steps) {}
}
