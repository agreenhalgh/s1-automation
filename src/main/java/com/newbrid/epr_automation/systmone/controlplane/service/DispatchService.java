package com.newbrid.epr_automation.systmone.controlplane.service;

import tools.jackson.databind.ObjectMapper;
import com.newbrid.epr_automation.systmone.common.dto.BotDefinition;
import com.newbrid.epr_automation.systmone.common.dto.RunJobMessage;
import com.newbrid.epr_automation.systmone.common.model.RunStatus;
import com.newbrid.epr_automation.systmone.controlplane.amqp.JobPublisher;
import com.newbrid.epr_automation.systmone.controlplane.domain.BotEntity;
import com.newbrid.epr_automation.systmone.controlplane.domain.RunEntity;
import com.newbrid.epr_automation.systmone.controlplane.repo.BotRepository;
import com.newbrid.epr_automation.systmone.controlplane.repo.RunRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Creates a run and dispatches it to the work queue for a worker to pick up
 * (FR-CP-01). The run is persisted as PENDING first, then published and marked
 * DISPATCHED, so a run always exists in the database before it can be reported against.
 */
@Service
@Profile("controlplane")
public class DispatchService {

    private final BotRepository bots;
    private final RunRepository runs;
    private final JobPublisher publisher;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public DispatchService(BotRepository bots, RunRepository runs, JobPublisher publisher,
                           AuditService audit, ObjectMapper mapper) {
        this.bots = bots;
        this.runs = runs;
        this.publisher = publisher;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional
    public String dispatch(String botId, Map<String, String> input, String capabilityTag, String principal) {
        BotEntity bot = bots.findById(botId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown bot: " + botId));
        BotDefinition definition = readDefinition(bot);

        String runId = UUID.randomUUID().toString();
        RunEntity run = new RunEntity(runId, botId, bot.getName(), capabilityTag, toJson(input));
        run.setStatus(RunStatus.PENDING);
        runs.save(run);

        RunJobMessage job = new RunJobMessage(runId, definition, input, capabilityTag, 1,
                Instant.now().toEpochMilli());
        publisher.publish(job);

        run.setStatus(RunStatus.DISPATCHED);
        run.setDispatchedAt(Instant.now());
        runs.save(run);

        audit.record(principal, "DISPATCH", botId, runId, "capabilityTag=" + capabilityTag);
        return runId;
    }

    private BotDefinition readDefinition(BotEntity bot) {
        try {
            return mapper.readValue(bot.getDefinitionJson(), BotDefinition.class);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt bot definition for " + bot.getId(), e);
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
