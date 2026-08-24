package com.newbrid.epr_automation.systmone.controlplane.repo;

import com.newbrid.epr_automation.systmone.controlplane.domain.WorkerAgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkerAgentRepository extends JpaRepository<WorkerAgentEntity, String> {
    List<WorkerAgentEntity> findAllByOrderByLastSeenAtDesc();

    /** Backs the agent-health gauges in {@code service.RunMetrics} (IDLE / BUSY). */
    long countByStatus(String status);
}
