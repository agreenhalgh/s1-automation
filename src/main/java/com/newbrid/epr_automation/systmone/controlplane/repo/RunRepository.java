package com.newbrid.epr_automation.systmone.controlplane.repo;

import com.newbrid.epr_automation.systmone.controlplane.domain.RunEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RunRepository extends JpaRepository<RunEntity, String> {
    Page<RunEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
    List<RunEntity> findByBotIdOrderByCreatedAtDesc(String botId);
}
