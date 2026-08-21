package com.newbrid.epr_automation.systmone.controlplane.web.ui;

import com.newbrid.epr_automation.systmone.controlplane.repo.RunRepository;
import com.newbrid.epr_automation.systmone.controlplane.repo.StepResultRepository;
import com.newbrid.epr_automation.systmone.controlplane.service.DispatchService;
import com.newbrid.epr_automation.systmone.controlplane.service.RegistryService;
import com.newbrid.epr_automation.systmone.controlplane.service.WorkerService;
import com.newbrid.epr_automation.systmone.controlplane.web.dto.WebDtos;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/** Operator dashboard: launch runs, watch live status, drill into run detail (FR-CP-03). */
@Controller
@Profile("controlplane")
public class DashboardController {

    private final RunRepository runs;
    private final StepResultRepository steps;
    private final WorkerService workers;
    private final RegistryService registry;
    private final DispatchService dispatch;

    public DashboardController(RunRepository runs, StepResultRepository steps, WorkerService workers,
                              RegistryService registry, DispatchService dispatch) {
        this.runs = runs;
        this.steps = steps;
        this.workers = workers;
        this.registry = registry;
        this.dispatch = dispatch;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("runs", runs.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 50)).getContent());
        model.addAttribute("workers", workers.list());
        model.addAttribute("bots", registry.listBots());
        return "index";
    }

    @GetMapping("/runs/{id}")
    public String run(@PathVariable String id, Model model) {
        var run = runs.findById(id).orElse(null);
        model.addAttribute("run", run);
        // Execution order, one row per execution: a run that looped shows every pass it took.
        model.addAttribute("steps", WebDtos.StepRow.of(steps.findByRunIdOrderByExecutionSeqAsc(id)));
        return "run";
    }

    @PostMapping("/ui/dispatch")
    public String dispatch(@RequestParam String botId,
                           @RequestParam(required = false) String capabilityTag,
                           Authentication auth) {
        String principal = auth != null ? auth.getName() : "ui";
        String runId = dispatch.dispatch(botId, Map.of(), emptyToNull(capabilityTag), principal);
        return "redirect:/runs/" + runId;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
