package com.newbrid.epr_automation.systmone.controlplane.service;

import tools.jackson.databind.ObjectMapper;
import com.newbrid.epr_automation.systmone.common.dto.ExceptionInfo;
import com.newbrid.epr_automation.systmone.common.dto.Heartbeat;
import com.newbrid.epr_automation.systmone.common.dto.StatusReport;
import com.newbrid.epr_automation.systmone.common.dto.StepReport;
import com.newbrid.epr_automation.systmone.common.model.RunStatus;
import com.newbrid.epr_automation.systmone.controlplane.domain.RunEntity;
import com.newbrid.epr_automation.systmone.controlplane.domain.StepResultEntity;
import com.newbrid.epr_automation.systmone.controlplane.domain.WorkerAgentEntity;
import com.newbrid.epr_automation.systmone.controlplane.repo.RunRepository;
import com.newbrid.epr_automation.systmone.controlplane.repo.StepResultRepository;
import com.newbrid.epr_automation.systmone.controlplane.repo.WorkerAgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Receives and persists worker-POSTed outcomes (FR-CP-02). Idempotent by design
 * (FR-CP-02a): step reports upsert on (runId, stepIndex); a terminal status is applied
 * once and later duplicates are ignored — so a redelivered/re-run job never produces
 * duplicate terminal records.
 */
@Service
@Profile("controlplane")
public class ResultService {

    private static final Logger log = LoggerFactory.getLogger(ResultService.class);

    private final RunRepository runs;
    private final StepResultRepository steps;
    private final WorkerAgentRepository workers;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public ResultService(RunRepository runs, StepResultRepository steps, WorkerAgentRepository workers,
                         AuditService audit, ObjectMapper mapper) {
        this.runs = runs;
        this.steps = steps;
        this.workers = workers;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional
    public void recordStep(String runId, String workerId, StepReport report) {
        requireRun(runId);
        // Keyed on the execution: a redelivered report for the same execution updates its row,
        // while a second visit to the same step under a conditional jump writes a new one.
        StepResultEntity e = steps.findByRunIdAndExecutionSeq(runId, report.sequence())
                .orElseGet(() -> new StepResultEntity(runId, report.sequence(), report.index()));
        e.setStepIndex(report.index());
        e.setName(report.name());
        e.setStatus(report.status());
        e.setResolvedLocator(report.resolvedLocator());
        e.setDurationMs(report.durationMs());
        e.setOutput(report.output());
        e.setScreenshotRef(report.screenshotRef());
        if (report.error() != null) {
            e.setErrorType(report.error().type());
            e.setErrorMessage(report.error().message());
            e.setErrorStack(report.error().stackTrace());
        }
        e.setTs(Instant.now());
        steps.save(e);
    }

    @Transactional
    public void recordStatus(String runId, String workerId, StatusReport report) {
        RunEntity run = requireRun(runId);

        // Idempotency guard: never overwrite a terminal state with another terminal report.
        if (run.getStatus() != null && run.getStatus().isTerminal()) {
            log.debug("Ignoring status {} for already-terminal run {}", report.status(), runId);
            return;
        }

        RunStatus incoming = report.status();
        if (workerId != null) run.setWorkerId(workerId);

        if (incoming == RunStatus.RUNNING) {
            run.setStatus(RunStatus.RUNNING);
            if (run.getStartedAt() == null) run.setStartedAt(Instant.now());
            run.setLastHeartbeatAt(Instant.now());
        } else if (incoming != null && incoming.isTerminal()) {
            run.setStatus(incoming);
            run.setFinishedAt(Instant.now());
            run.setOutputsJson(toJson(report.outputs()));
            ExceptionInfo err = report.error();
            if (err != null) {
                run.setErrorType(err.type());
                run.setErrorMessage(err.message());
                run.setErrorStack(err.stackTrace());
            }
            releaseWorker(workerId);
            audit.record(workerId, "RUN_" + incoming, run.getBotId(), runId, null);
        } else if (incoming != null) {
            run.setStatus(incoming);
        }
        runs.save(run);
    }

    @Transactional
    public void heartbeat(String runId, Heartbeat hb) {
        RunEntity run = requireRun(runId);
        run.setLastHeartbeatAt(Instant.now());
        if (run.getStatus() == RunStatus.DISPATCHED) {
            run.setStatus(RunStatus.RUNNING);
            if (run.getStartedAt() == null) run.setStartedAt(Instant.now());
        }
        runs.save(run);
        if (hb != null && hb.workerId() != null) {
            workers.findById(hb.workerId()).ifPresent(w -> {
                w.setLastSeenAt(Instant.now());
                w.setCurrentRunId(runId);
                w.setStatus("BUSY");
                workers.save(w);
            });
        }
    }

    private void releaseWorker(String workerId) {
        if (workerId == null) return;
        workers.findById(workerId).ifPresent(w -> {
            w.setCurrentRunId(null);
            w.setStatus("IDLE");
            w.setLastSeenAt(Instant.now());
            workers.save(w);
        });
    }

    private RunEntity requireRun(String runId) {
        return runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown run: " + runId));
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
