package com.newbrid.epr_automation.systmone.controlplane.web.dto;

import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.controlplane.domain.AuditEntity;
import com.newbrid.epr_automation.systmone.controlplane.domain.LocatorEntity;
import com.newbrid.epr_automation.systmone.controlplane.domain.RunEntity;
import com.newbrid.epr_automation.systmone.controlplane.domain.StepResultEntity;
import com.newbrid.epr_automation.systmone.controlplane.domain.TemplateEntity;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.ArrayList;
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

    /** POST /api/locators — store a named locator authored in the capture tool (FR-REG-01). */
    public record LocatorUpsertRequest(String name, String s1Version, LocatorDefinition definition) {}

    /**
     * GET /api/locators — a stored locator with its definition parsed back out, so a client can
     * read the offsets and template references rather than re-parsing the stored JSON.
     */
    public record LocatorSummary(String id, String name, String type, String s1Version, String owner,
                                 int version, String status, LocatorDefinition definition,
                                 Instant createdAt, Instant updatedAt) {

        public static LocatorSummary of(LocatorEntity e, LocatorDefinition definition) {
            return new LocatorSummary(e.getId(), e.getName(), e.getType(), e.getS1Version(), e.getOwner(),
                    e.getVersion(), e.getStatus() == null ? null : e.getStatus().name(), definition,
                    e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    /** GET /api/runs/{id} — run plus its step history, one row per execution. */
    public record RunDetail(RunEntity run, List<StepResultEntity> steps) {}

    /**
     * One audit entry, as the viewer shows it (NFR-SEC-05). A flattened copy rather than the
     * entity, so the template can only reach what the auditor is meant to see and cannot
     * navigate off into whatever the entity might gain later.
     *
     * @param at the timestamp rendered in UTC. The trail stores {@link Instant} and the database
     *           runs with {@code PGTZ=UTC}, so rendering in the reader's local zone would put a
     *           different time on the same evidence depending on who opened it
     */
    public record AuditRow(Long id, String at, String principal, String action, String botId,
                           String runId, String detail) {

        /** Long enough that the table would become unreadable if it were shown inline. */
        private static final int PREVIEW = 110;

        private static final DateTimeFormatter UTC_STAMP = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

        public static List<AuditRow> of(List<AuditEntity> entries) {
            return entries.stream().map(AuditRow::of).toList();
        }

        public static AuditRow of(AuditEntity e) {
            return new AuditRow(e.getId(),
                    e.getTs() == null ? "" : UTC_STAMP.format(e.getTs()),
                    e.getPrincipal(), e.getAction(), e.getBotId(), e.getRunId(), e.getDetail());
        }

        public boolean hasDetail() {
            return detail != null && !detail.isBlank();
        }

        /** Whether the detail needs an expander rather than being shown in the cell. */
        public boolean detailTruncated() {
            return detail != null && detail.length() > PREVIEW;
        }

        public String detailPreview() {
            if (detail == null) return "";
            return detail.length() <= PREVIEW ? detail : detail.substring(0, PREVIEW) + "…";
        }
    }

    /**
     * One execution, as the run page shows it: the stored row plus where this visit sits among
     * the visits to that same step.
     *
     * <p>A conditional flow can run one step several times, and a table of identical-looking rows
     * is how you fail to notice that. {@code visit} and {@code visits} give each row the context
     * to say "pass 2 of 4" instead.</p>
     *
     * @param visit  which visit to this step this row is, counting from 1
     * @param visits how many times the run visited this step in total
     */
    public record StepRow(StepResultEntity step, int visit, int visits) {

        public boolean repeated() {
            return visits > 1;
        }

        /** Rows in execution order, each labelled with its place among visits to the same step. */
        public static List<StepRow> of(List<StepResultEntity> executions) {
            Map<Integer, Long> totals = executions.stream()
                    .collect(Collectors.groupingBy(StepResultEntity::getStepIndex, Collectors.counting()));
            Map<Integer, Integer> seen = new HashMap<>();
            List<StepRow> rows = new ArrayList<>(executions.size());
            for (StepResultEntity execution : executions) {
                int position = execution.getStepIndex();
                int visit = seen.merge(position, 1, Integer::sum);
                rows.add(new StepRow(execution, visit, totals.getOrDefault(position, 1L).intValue()));
            }
            return rows;
        }
    }
}
