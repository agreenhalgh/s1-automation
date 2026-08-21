package com.newbrid.epr_automation.systmone.controlplane.web.api;

import com.newbrid.epr_automation.systmone.controlplane.domain.RunEntity;
import com.newbrid.epr_automation.systmone.controlplane.repo.RunRepository;
import com.newbrid.epr_automation.systmone.controlplane.repo.StepResultRepository;
import com.newbrid.epr_automation.systmone.controlplane.service.DispatchService;
import com.newbrid.epr_automation.systmone.controlplane.web.dto.WebDtos.DispatchRequest;
import com.newbrid.epr_automation.systmone.controlplane.web.dto.WebDtos.DispatchResponse;
import com.newbrid.epr_automation.systmone.controlplane.web.dto.WebDtos.RunDetail;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Dispatch and query runs (FR-CP-01, FR-CP-05). */
@RestController
@RequestMapping("/api/runs")
@Profile("controlplane")
public class RunApiController {

    private final DispatchService dispatch;
    private final RunRepository runs;
    private final StepResultRepository steps;

    public RunApiController(DispatchService dispatch, RunRepository runs, StepResultRepository steps) {
        this.dispatch = dispatch;
        this.runs = runs;
        this.steps = steps;
    }

    @PostMapping
    public DispatchResponse dispatch(@RequestBody DispatchRequest req) {
        String runId = dispatch.dispatch(req.botId(), req.input(), req.capabilityTag(), "worker-api");
        return new DispatchResponse(runId);
    }

    @GetMapping
    public List<RunEntity> list(@RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "50") int size) {
        return runs.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size)).getContent();
    }

    @GetMapping("/{id}")
    public ResponseEntity<RunDetail> get(@PathVariable String id) {
        return runs.findById(id)
                .map(run -> ResponseEntity.ok(new RunDetail(run, steps.findByRunIdOrderByExecutionSeqAsc(id))))
                .orElse(ResponseEntity.notFound().build());
    }
}
