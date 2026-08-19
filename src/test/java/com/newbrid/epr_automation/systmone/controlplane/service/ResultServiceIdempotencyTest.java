package com.newbrid.epr_automation.systmone.controlplane.service;

import com.newbrid.epr_automation.systmone.common.dto.ExceptionInfo;
import com.newbrid.epr_automation.systmone.common.dto.StatusReport;
import com.newbrid.epr_automation.systmone.common.model.RunStatus;
import com.newbrid.epr_automation.systmone.controlplane.domain.RunEntity;
import com.newbrid.epr_automation.systmone.controlplane.domain.WorkerAgentEntity;
import com.newbrid.epr_automation.systmone.controlplane.repo.RunRepository;
import com.newbrid.epr_automation.systmone.controlplane.repo.StepResultRepository;
import com.newbrid.epr_automation.systmone.controlplane.repo.WorkerAgentRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Proves the results endpoint applies a terminal outcome once and ignores duplicates (FR-CP-02a). */
class ResultServiceIdempotencyTest {

    @Test
    void terminalStatusIsAppliedOnceAndDuplicatesIgnored() {
        RunRepository runs = mock(RunRepository.class);
        StepResultRepository steps = mock(StepResultRepository.class);
        WorkerAgentRepository workers = mock(WorkerAgentRepository.class);
        AuditService audit = mock(AuditService.class);
        tools.jackson.databind.ObjectMapper mapper = mock(tools.jackson.databind.ObjectMapper.class);

        RunEntity run = new RunEntity("r1", "bot", "Bot", null, "{}");
        run.setStatus(RunStatus.RUNNING);
        when(runs.findById("r1")).thenReturn(Optional.of(run));
        when(workers.findById(anyString())).thenReturn(Optional.<WorkerAgentEntity>empty());

        ResultService svc = new ResultService(runs, steps, workers, audit, mapper);

        // First terminal report wins.
        svc.recordStatus("r1", "w1", new StatusReport(RunStatus.SUCCEEDED, null, Map.of()));
        assertEquals(RunStatus.SUCCEEDED, run.getStatus());

        // A duplicate/late redelivered report must not overwrite the terminal state.
        svc.recordStatus("r1", "w1",
                new StatusReport(RunStatus.FAILED, new ExceptionInfo("X", "late", null, null), Map.of()));
        assertEquals(RunStatus.SUCCEEDED, run.getStatus(), "terminal state must be immutable");

        // The terminal transition is audited exactly once.
        verify(audit, times(1)).record(eq("w1"), eq("RUN_SUCCEEDED"), eq("bot"), eq("r1"), isNull());
    }
}
