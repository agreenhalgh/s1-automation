package com.newbrid.epr_automation.systmone.controlplane.repo;

import com.newbrid.epr_automation.systmone.controlplane.domain.BotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BotRepository extends JpaRepository<BotEntity, String> {
    List<BotEntity> findAllByOrderByUpdatedAtDesc();
}
