package com.newbrid.epr_automation.systmone.controlplane.web.api;

import com.newbrid.epr_automation.systmone.controlplane.service.RegistryService;
import com.newbrid.epr_automation.systmone.controlplane.web.dto.WebDtos.TemplateSummary;
import com.newbrid.epr_automation.systmone.controlplane.web.dto.WebDtos.TemplateUploadRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Template registry API: developers upload captured partial screenshots (FR-TOOL-02,
 * FR-SR-08), browse what is registered, and workers fetch the image bytes by name to
 * resolve image locators. A template's {@code name} is its registry key — the value bots
 * cite as {@code templateRef} — so both read endpoints are keyed by name.
 */
@RestController
@RequestMapping("/api/templates")
@Profile("controlplane")
public class TemplateApiController {

    private final RegistryService registry;

    public TemplateApiController(RegistryService registry) {
        this.registry = registry;
    }

    @PostMapping
    public Map<String, String> upload(@RequestBody TemplateUploadRequest req) {
        byte[] data = Base64.getDecoder().decode(req.dataBase64());
        double threshold = req.threshold() != null ? req.threshold() : 0.90;
        String id = registry.saveTemplate(req.name(), req.s1Version(), threshold,
                req.screenContext(), "api", data);
        return Map.of("templateId", id, "name", req.name());
    }

    /**
     * Registry metadata for every template, ordered by name and paged like {@code /api/runs}.
     * Pass {@code s1Version} to see only the templates captured for one SystmOne release.
     */
    @GetMapping
    public List<TemplateSummary> list(@RequestParam(required = false) String s1Version,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        return registry.listTemplates(s1Version, PageRequest.of(page, size)).stream()
                .map(TemplateSummary::of).toList();
    }

    /** Registry metadata for one template; 404 when the name is not registered. */
    @GetMapping("/{name}")
    public ResponseEntity<TemplateSummary> get(@PathVariable String name) {
        return registry.getTemplateByName(name)
                .map(t -> ResponseEntity.ok(TemplateSummary.of(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Raw image bytes, consumed by workers' template store. */
    @GetMapping("/{name}/image")
    public ResponseEntity<byte[]> image(@PathVariable String name) {
        return registry.getTemplateByName(name)
                .map(t -> ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(t.getData()))
                .orElse(ResponseEntity.notFound().build());
    }
}
