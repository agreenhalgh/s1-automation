package com.newbrid.epr_automation.systmone.authoring.web;

import com.newbrid.epr_automation.systmone.authoring.AuthoringService;
import com.newbrid.epr_automation.systmone.authoring.RegistryClient;
import com.newbrid.epr_automation.systmone.authoring.RegistryTemplateStore;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.CaptureCommand;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.CaptureView;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.InspectCommand;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.InspectView;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.JabLocatorCommand;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.SavedLocatorView;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.LocateCommand;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.LocateResult;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.OffsetLocatorCommand;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.OffsetLocatorView;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.TemplateFromSelection;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.TestCommand;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.TestResult;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.WorkerView;
import com.newbrid.epr_automation.systmone.controlplane.web.dto.WebDtos.LocatorSummary;
import com.newbrid.epr_automation.systmone.controlplane.web.dto.WebDtos.TemplateSummary;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The JSON surface the capture page drives: capture, crop, locate, derive, test. */
@RestController
@RequestMapping("/authoring/api")
@Profile("authoring")
public class AuthoringApiController {

    private final AuthoringService authoring;
    private final RegistryClient registry;
    private final RegistryTemplateStore templateImages;

    public AuthoringApiController(AuthoringService authoring, RegistryClient registry,
                                 RegistryTemplateStore templateImages) {
        this.authoring = authoring;
        this.registry = registry;
        this.templateImages = templateImages;
    }

    @GetMapping("/workers")
    public List<WorkerView> workers() {
        return registry.workers();
    }

    /** @param refresh drop cached template images first, when another author may have replaced one */
    @GetMapping("/templates")
    public List<TemplateSummary> templates(@RequestParam(defaultValue = "false") boolean refresh) {
        if (refresh) templateImages.invalidateAll();
        return registry.templates();
    }

    @GetMapping("/locators")
    public List<LocatorSummary> locators() {
        return registry.locators();
    }

    @PostMapping("/captures")
    public CaptureView capture(@RequestBody CaptureCommand cmd) {
        return authoring.capture(cmd.workerId(), cmd.activateWindow());
    }

    @GetMapping("/captures")
    public List<CaptureView> captures() {
        return authoring.heldCaptures();
    }

    @GetMapping("/captures/{id}")
    public CaptureView capture(@PathVariable String id) {
        return authoring.held(id);
    }

    @DeleteMapping("/captures/{id}")
    public Map<String, Object> discard(@PathVariable String id) {
        return Map.of("discarded", authoring.discard(id));
    }

    @DeleteMapping("/captures")
    public Map<String, Object> discardAll() {
        return Map.of("discarded", authoring.discardAll());
    }

    @PostMapping("/captures/{id}/locate")
    public List<LocateResult> locate(@PathVariable String id, @RequestBody LocateCommand cmd) {
        return authoring.locate(id, cmd.templateRefs());
    }

    /** What accessible element is under this point? Reads the worker's live tree (FR-TOOL-03). */
    @PostMapping("/captures/{id}/inspect")
    public InspectView inspect(@PathVariable String id, @RequestBody InspectCommand cmd) {
        return authoring.inspect(id, cmd.x(), cmd.y());
    }

    /** What has focus in SystmOne right now? No coordinates needed (FR-TOOL-03). */
    @PostMapping("/captures/{id}/focus")
    public InspectView focused(@PathVariable String id) {
        return authoring.inspectFocused(id);
    }

    /** Store an attribute locator under a name a bot can reference. */
    @PostMapping("/locators/jab")
    public SavedLocatorView saveJabLocator(@RequestBody JabLocatorCommand cmd) {
        return authoring.saveJabLocator(cmd);
    }

    @PostMapping("/templates")
    public LocateResult saveTemplate(@RequestBody TemplateFromSelection cmd) {
        return authoring.saveTemplate(cmd);
    }

    @PostMapping("/locators")
    public OffsetLocatorView saveLocator(@RequestBody OffsetLocatorCommand cmd) {
        return authoring.saveOffsetLocator(cmd);
    }

    @PostMapping("/test")
    public TestResult test(@RequestBody TestCommand cmd) {
        return authoring.test(cmd);
    }
}
