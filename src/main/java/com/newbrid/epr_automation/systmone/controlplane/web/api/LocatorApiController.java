package com.newbrid.epr_automation.systmone.controlplane.web.api;

import com.newbrid.epr_automation.systmone.controlplane.domain.LocatorEntity;
import com.newbrid.epr_automation.systmone.controlplane.service.RegistryService;
import com.newbrid.epr_automation.systmone.controlplane.web.dto.WebDtos.LocatorSummary;
import com.newbrid.epr_automation.systmone.controlplane.web.dto.WebDtos.LocatorUpsertRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Named locators in the registry (FR-REG-01/02, FR-CP-08) — the anchor-relative image locators
 * the capture tool authors, kept where a bot author can find and cite them.
 *
 * <p>A locator's {@code name} is its key: re-saving one revises it in place rather than adding
 * a near-duplicate, since authoring is iterative by nature.</p>
 */
@RestController
@RequestMapping("/api/locators")
@Profile("controlplane")
public class LocatorApiController {

    private final RegistryService registry;

    public LocatorApiController(RegistryService registry) {
        this.registry = registry;
    }

    @PostMapping
    public Map<String, String> save(@RequestBody LocatorUpsertRequest req) {
        String id = registry.saveLocator(req.name(), req.s1Version(), "api", req.definition());
        return Map.of("locatorId", id, "name", req.name());
    }

    /** Every locator, most recently revised first. */
    @GetMapping
    public List<LocatorSummary> list() {
        return registry.listLocators().stream().map(this::summarise).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<LocatorSummary> get(@PathVariable String id) {
        return registry.getLocator(id).map(this::summarise).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private LocatorSummary summarise(LocatorEntity e) {
        return LocatorSummary.of(e, registry.readLocatorDefinition(e));
    }
}
