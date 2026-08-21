package com.newbrid.epr_automation.systmone.controlplane.repo;

import com.newbrid.epr_automation.systmone.controlplane.domain.StepResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StepResultRepository extends JpaRepository<StepResultEntity, Long> {

    /** The row a redelivered report updates: one execution, not one step. */
    Optional<StepResultEntity> findByRunIdAndExecutionSeq(String runId, int executionSeq);

    /**
     * Every execution in the order it happened. Ordering by sequence rather than by step index
     * is what makes a conditional flow readable: sorting by position would interleave a loop's
     * passes and present them as though the run went up and down the definition.
     */
    List<StepResultEntity> findByRunIdOrderByExecutionSeqAsc(String runId);
}
