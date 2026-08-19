package com.newbrid.epr_automation.systmone.controlplane.service;

import com.newbrid.epr_automation.systmone.controlplane.domain.AuditEntity;
import com.newbrid.epr_automation.systmone.controlplane.repo.AuditRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Writes append-only audit records (NFR-SEC-05). */
@Service
@Profile("controlplane")
public class AuditService {

    private final AuditRepository repo;

    public AuditService(AuditRepository repo) {
        this.repo = repo;
    }

    public void record(String principal, String action, String botId, String runId, String detail) {
        repo.save(new AuditEntity(principal, action, botId, runId, detail));
    }
}
