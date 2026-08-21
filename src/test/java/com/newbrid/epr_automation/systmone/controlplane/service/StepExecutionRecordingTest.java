package com.newbrid.epr_automation.systmone.controlplane.service;

import com.newbrid.epr_automation.systmone.common.dto.StepReport;
import com.newbrid.epr_automation.systmone.common.model.RunStatus;
import com.newbrid.epr_automation.systmone.common.model.StepStatus;
import com.newbrid.epr_automation.systmone.controlplane.domain.RunEntity;
import com.newbrid.epr_automation.systmone.controlplane.domain.StepResultEntity;
import com.newbrid.epr_automation.systmone.controlplane.domain.WorkerAgentEntity;
import com.newbrid.epr_automation.systmone.controlplane.repo.RunRepository;
import com.newbrid.epr_automation.systmone.controlplane.repo.StepResultRepository;
import com.newbrid.epr_automation.systmone.controlplane.repo.WorkerAgentRepository;
import com.newbrid.epr_automation.systmone.controlplane.web.dto.WebDtos.StepRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One stored row per step <em>execution</em>, not per step. A conditional flow can send a run
 * back to a step it has already done, and both visits are part of what happened.
 */
class StepExecutionRecordingTest {

    private final RunRepository runs = mock(RunRepository.class);
    private final StepResultRepository steps = mock(StepResultRepository.class);
    private final WorkerAgentRepository workers = mock(WorkerAgentRepository.class);
    private final List<StepResultEntity> saved = new ArrayList<>();

    private ResultService service() {
        RunEntity run = new RunEntity("r1", "bot", "Bot", null, "{}");
        run.setStatus(RunStatus.RUNNING);
        when(runs.findById("r1")).thenReturn(Optional.of(run));
        when(workers.findById(anyString())).thenReturn(Optional.<WorkerAgentEntity>empty());

        // Stand in for the unique index on (runId, executionSeq): a lookup finds a row already
        // written for that execution, and nothing else.
        when(steps.findByRunIdAndExecutionSeq(anyString(), anyInt())).thenAnswer(call ->
                saved.stream()
                        .filter(e -> e.getRunId().equals(call.getArgument(0))
                                && e.getExecutionSeq() == (int) call.getArgument(1))
                        .findFirst());
        when(steps.save(any(StepResultEntity.class))).thenAnswer(call -> {
            StepResultEntity e = call.getArgument(0);
            if (!saved.contains(e)) saved.add(e);
            return e;
        });
        return new ResultService(runs, steps, workers, mock(AuditService.class),
                mock(tools.jackson.databind.ObjectMapper.class));
    }

    private static StepReport report(int position, int sequence, StepStatus status) {
        return new StepReport(position, sequence, "step " + position, status, "jab", 12, null, null, null);
    }

    /** The case the old (runId, stepIndex) key could not represent. */
    @Test
    void keepsBothVisitsWhenTheFlowJumpsBackToAStep() {
        ResultService svc = service();

        svc.recordStep("r1", "w1", report(3, 1, StepStatus.FAILED));    // first pass: not there yet
        svc.recordStep("r1", "w1", report(4, 2, StepStatus.SUCCEEDED)); // page down
        svc.recordStep("r1", "w1", report(3, 3, StepStatus.SUCCEEDED)); // back to 3, found it

        assertEquals(3, saved.size(), "three executions happened, so three rows");
        assertEquals(List.of(1, 2, 3), saved.stream().map(StepResultEntity::getExecutionSeq).toList());
        assertEquals(List.of(3, 4, 3), saved.stream().map(StepResultEntity::getStepIndex).toList());
        assertEquals(StepStatus.FAILED, saved.getFirst().getStatus(),
                "the earlier pass keeps its own outcome instead of being overwritten");
    }

    /** Redelivery still has to be idempotent — that is what the sequence is keyed on (FR-CP-02a). */
    @Test
    void aRedeliveredReportUpdatesItsOwnRowRatherThanAddingOne() {
        ResultService svc = service();

        svc.recordStep("r1", "w1", report(3, 1, StepStatus.FAILED));
        svc.recordStep("r1", "w1", report(3, 1, StepStatus.SUCCEEDED));

        assertEquals(1, saved.size(), "same execution reported twice is one row");
        assertEquals(StepStatus.SUCCEEDED, saved.getFirst().getStatus());
    }

    // ---- how the run page labels them ----

    @Test
    void labelsEachVisitToARepeatedStep() {
        List<StepRow> rows = StepRow.of(List.of(
                row(1, 3), row(2, 4), row(3, 3), row(4, 5)));

        assertEquals(List.of(1, 1, 2, 1), rows.stream().map(StepRow::visit).toList());
        assertEquals(List.of(2, 1, 2, 1), rows.stream().map(StepRow::visits).toList());
        assertTrue(rows.get(0).repeated(), "step 3 ran twice, so its rows say so");
        assertFalse(rows.get(1).repeated(), "step 4 ran once and needs no badge");
    }

    /** Rows stay in execution order: sorting by position would present a loop as a walk. */
    @Test
    void keepsExecutionOrder() {
        List<StepRow> rows = StepRow.of(List.of(row(1, 3), row(2, 4), row(3, 3)));

        assertEquals(List.of(1, 2, 3), rows.stream().map(r -> r.step().getExecutionSeq()).toList());
    }

    private static StepResultEntity row(int sequence, int position) {
        return new StepResultEntity("r1", sequence, position);
    }
}
