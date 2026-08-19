package com.newbrid.epr_automation.systmone.controlplane.web.api;

import com.newbrid.epr_automation.systmone.common.dto.BotDefinition;
import com.newbrid.epr_automation.systmone.controlplane.domain.BotEntity;
import com.newbrid.epr_automation.systmone.controlplane.service.RegistryService;
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

/** Register and query bots (FR-CP-08). */
@RestController
@RequestMapping("/api/bots")
@Profile("controlplane")
public class BotApiController {

    private final RegistryService registry;

    public BotApiController(RegistryService registry) {
        this.registry = registry;
    }

    @PostMapping
    public Map<String, String> register(@RequestBody BotDefinition definition) {
        return Map.of("botId", registry.registerBot(definition, "api"));
    }

    @GetMapping
    public List<BotEntity> list() {
        return registry.listBots();
    }

    @GetMapping("/{id}")
    public ResponseEntity<BotDefinition> get(@PathVariable String id) {
        return registry.getBotDefinition(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
