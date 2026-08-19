package com.newbrid.epr_automation.systmone.controlplane.repo;

import com.newbrid.epr_automation.systmone.controlplane.domain.LocatorEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LocatorRepository extends JpaRepository<LocatorEntity, String> {
    List<LocatorEntity> findAllByOrderByUpdatedAtDesc();
}
