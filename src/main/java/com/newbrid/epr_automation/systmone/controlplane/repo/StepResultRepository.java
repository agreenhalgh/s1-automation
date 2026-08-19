package com.newbrid.epr_automation.systmone.controlplane.repo;

import com.newbrid.epr_automation.systmone.controlplane.domain.StepResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StepResultRepository extends JpaRepository<StepResultEntity, Long> {
    Optional<StepResultEntity> findByRunIdAndStepIndex(String runId, int stepIndex);
    List<StepResultEntity> findByRunIdOrderByStepIndexAsc(String runId);
}
