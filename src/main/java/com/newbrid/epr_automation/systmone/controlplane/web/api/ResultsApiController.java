package com.newbrid.epr_automation.systmone.controlplane.web.api;

import com.newbrid.epr_automation.systmone.common.dto.Heartbeat;
import com.newbrid.epr_automation.systmone.common.dto.StatusReport;
import com.newbrid.epr_automation.systmone.common.dto.StepReport;
import com.newbrid.epr_automation.systmone.controlplane.service.ResultService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The results endpoint workers POST to (FR-CP-02). Concurrent POSTs from many workers are
 * correlated by {@code runId} and applied idempotently (FR-CP-02a).
 */
@RestController
@RequestMapping("/api/runs/{runId}")
@Profile("controlplane")
public class ResultsApiController {

    private final ResultService results;

    public ResultsApiController(ResultService results) {
        this.results = results;
    }

    @PostMapping("/steps")
    public ResponseEntity<Void> step(@PathVariable String runId,
                                     @RequestHeader(value = "X-Worker-Id", required = false) String workerId,
                                     @RequestBody StepReport report) {
        results.recordStep(runId, workerId, report);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/status")
    public ResponseEntity<Void> status(@PathVariable String runId,
                                       @RequestHeader(value = "X-Worker-Id", required = false) String workerId,
                                       @RequestBody StatusReport report) {
        results.recordStatus(runId, workerId, report);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@PathVariable String runId, @RequestBody Heartbeat hb) {
        results.heartbeat(runId, hb);
        return ResponseEntity.accepted().build();
    }
}
