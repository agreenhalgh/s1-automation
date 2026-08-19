package com.newbrid.epr_automation.systmone.controlplane.repo;

import com.newbrid.epr_automation.systmone.controlplane.domain.LocatorEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LocatorRepository extends JpaRepository<LocatorEntity, String> {
    List<LocatorEntity> findAllByOrderByUpdatedAtDesc();
    Optional<LocatorEntity> findByName(String name);
}
