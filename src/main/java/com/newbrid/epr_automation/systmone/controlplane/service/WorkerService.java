package com.newbrid.epr_automation.systmone.controlplane.service;

import com.newbrid.epr_automation.systmone.common.dto.WorkerRegistration;
import com.newbrid.epr_automation.systmone.controlplane.domain.WorkerAgentEntity;
import com.newbrid.epr_automation.systmone.controlplane.repo.WorkerAgentRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Tracks worker registration and health (FR-CP-06). */
@Service
@Profile("controlplane")
public class WorkerService {

    private final WorkerAgentRepository repo;

    public WorkerService(WorkerAgentRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void register(WorkerRegistration reg) {
        WorkerAgentEntity w = repo.findById(reg.workerId()).orElseGet(() -> new WorkerAgentEntity(reg.workerId()));
        w.setHost(reg.host());
        w.setS1Version(reg.s1Version());
        w.setScreenWidth(reg.screenWidth());
        w.setScreenHeight(reg.screenHeight());
        w.setCapabilities(String.join(",", reg.capabilities()));
        w.setAdapters(String.join(",", reg.enabledAdapters()));
        w.setStatus("IDLE");
        w.setLastSeenAt(Instant.now());
        repo.save(w);
    }

    public List<WorkerAgentEntity> list() {
        return repo.findAllByOrderByLastSeenAtDesc();
    }
}
