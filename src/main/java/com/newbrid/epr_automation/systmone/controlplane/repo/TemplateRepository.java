package com.newbrid.epr_automation.systmone.controlplane.repo;

import com.newbrid.epr_automation.systmone.controlplane.domain.TemplateEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TemplateRepository extends JpaRepository<TemplateEntity, String> {
    Optional<TemplateEntity> findByName(String name);
    Page<TemplateEntity> findAllByOrderByNameAsc(Pageable pageable);
    Page<TemplateEntity> findByS1VersionOrderByNameAsc(String s1Version, Pageable pageable);
}
