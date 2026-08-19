package com.newbrid.epr_automation.systmone.authoring.web;

import com.newbrid.epr_automation.systmone.authoring.AuthoringProperties;
import com.newbrid.epr_automation.systmone.authoring.AuthoringService;
import com.newbrid.epr_automation.systmone.authoring.RegistryClient;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.CaptureView;
import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.WorkerView;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/** The two pages of the capture tool: pick a worker and capture, then work on a capture. */
@Controller
@Profile("authoring")
public class AuthoringUiController {

    private final AuthoringService authoring;
    private final RegistryClient registry;
    private final AuthoringProperties props;

    public AuthoringUiController(AuthoringService authoring, RegistryClient registry,
                                AuthoringProperties props) {
        this.authoring = authoring;
        this.registry = registry;
        this.props = props;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("workers", safeWorkers(model));
        model.addAttribute("captures", authoring.heldCaptures());
        model.addAttribute("controlPlaneUrl", props.getControlPlaneUrl());
        model.addAttribute("s1Version", props.getS1Version());
        return "authoring/index";
    }

    @GetMapping("/captures/{id}")
    public String capture(@PathVariable String id, Model model) {
        CaptureView capture = authoring.held(id);
        model.addAttribute("capture", capture);
        model.addAttribute("defaultThreshold", props.getDefaultThreshold());
        model.addAttribute("s1Version", props.getS1Version());
        return "authoring/capture";
    }

    /**
     * The capture's PNG. Served from memory, marked no-store: a screenshot of a clinical system
     * must not be left in a browser cache on the author's machine (§5.4).
     */
    @GetMapping("/authoring/captures/{id}.png")
    @ResponseBody
    public ResponseEntity<byte[]> image(@PathVariable String id) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header("Cache-Control", "no-store")
                .body(authoring.imageBytes(id));
    }

    /**
     * The control plane being unreachable must not blank the page — the author still needs to see
     * held captures and the reason the worker list is empty.
     */
    private List<WorkerView> safeWorkers(Model model) {
        try {
            return registry.workers();
        } catch (Exception e) {
            model.addAttribute("registryError", "Could not reach the control plane at "
                    + props.getControlPlaneUrl() + ": " + e.getMessage());
            return List.of();
        }
    }
}
