package com.newbrid.epr_automation.systmone.controlplane.web.api;

import com.newbrid.epr_automation.systmone.common.dto.WorkerRegistration;
import com.newbrid.epr_automation.systmone.controlplane.domain.WorkerAgentEntity;
import com.newbrid.epr_automation.systmone.controlplane.service.WorkerService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Worker registration and health (FR-CP-06, FR-WRK-08). */
@RestController
@RequestMapping("/api/workers")
@Profile("controlplane")
public class WorkerApiController {

    private final WorkerService workers;

    public WorkerApiController(WorkerService workers) {
        this.workers = workers;
    }

    @PostMapping("/register")
    public ResponseEntity<Void> register(@RequestBody WorkerRegistration registration) {
        workers.register(registration);
        return ResponseEntity.accepted().build();
    }

    @GetMapping
    public List<WorkerAgentEntity> list() {
        return workers.list();
    }
}
