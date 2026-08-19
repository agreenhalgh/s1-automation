package com.newbrid.epr_automation.systmone.controlplane.service;

import tools.jackson.databind.ObjectMapper;
import com.newbrid.epr_automation.systmone.common.dto.BotDefinition;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.model.LocatorType;
import com.newbrid.epr_automation.systmone.controlplane.domain.BotEntity;
import com.newbrid.epr_automation.systmone.controlplane.domain.LocatorEntity;
import com.newbrid.epr_automation.systmone.controlplane.domain.TemplateEntity;
import com.newbrid.epr_automation.systmone.controlplane.repo.BotRepository;
import com.newbrid.epr_automation.systmone.controlplane.repo.LocatorRepository;
import com.newbrid.epr_automation.systmone.controlplane.repo.TemplateRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Manages the bot, template and locator registries (FR-CP-08, FR-REG-01..04). */
@Service
@Profile("controlplane")
public class RegistryService {

    private final BotRepository bots;
    private final TemplateRepository templates;
    private final LocatorRepository locators;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final BotDefinitionValidator validator;

    public RegistryService(BotRepository bots, TemplateRepository templates, LocatorRepository locators,
                           AuditService audit, ObjectMapper mapper, BotDefinitionValidator validator) {
        this.bots = bots;
        this.templates = templates;
        this.locators = locators;
        this.audit = audit;
        this.mapper = mapper;
        this.validator = validator;
    }

    // ---- bots ----

    @Transactional
    public String registerBot(BotDefinition def, String principal) {
        // Reject unresolved template references here rather than letting a dispatched run
        // discover them on a worker (FR-REG-04).
        validator.validate(def);
        String json = toJson(def);
        BotEntity entity = bots.findById(def.botId()).orElse(null);
        if (entity == null) {
            entity = new BotEntity(def.botId(), def.name(), def.version(), def.s1Version(), json);
        } else {
            entity.setName(def.name());
            entity.setVersion(def.version());
            entity.setS1Version(def.s1Version());
            entity.setDefinitionJson(json);
            entity.touch();
        }
        bots.save(entity);
        audit.record(principal, "REGISTER_BOT", def.botId(), null, "version=" + def.version());
        return def.botId();
    }

    public List<BotEntity> listBots() {
        return bots.findAllByOrderByUpdatedAtDesc();
    }

    public Optional<BotDefinition> getBotDefinition(String botId) {
        return bots.findById(botId).map(this::readDefinition);
    }

    public Optional<BotEntity> getBot(String botId) {
        return bots.findById(botId);
    }

    // ---- templates ----

    @Transactional
    public String saveTemplate(String name, String s1Version, double threshold, String screenContext,
                               String createdBy, byte[] data) {
        String id = UUID.randomUUID().toString();
        TemplateEntity existing = templates.findByName(name).orElse(null);
        if (existing != null) id = existing.getId();
        TemplateEntity t = new TemplateEntity(id, name, s1Version, threshold, screenContext,
                createdBy, sha256(data), data);
        templates.save(t);
        audit.record(createdBy, "SAVE_TEMPLATE", null, null, "template=" + name);
        return id;
    }

    /** A single template by its registry name — the key bots reference as {@code templateRef}. */
    public Optional<TemplateEntity> getTemplateByName(String name) {
        return templates.findByName(name);
    }

    /**
     * One page of templates ordered by name. A non-blank {@code s1Version} narrows the list to
     * the templates captured for that SystmOne release (FR-REG-02).
     */
    public List<TemplateEntity> listTemplates(String s1Version, Pageable pageable) {
        Page<TemplateEntity> page = s1Version == null || s1Version.isBlank()
                ? templates.findAllByOrderByNameAsc(pageable)
                : templates.findByS1VersionOrderByNameAsc(s1Version, pageable);
        return page.getContent();
    }

    // ---- locators ----

    /**
     * Store a named locator, upserting by name so re-authoring one in the capture tool revises
     * it rather than accumulating near-duplicates. A revised definition bumps the entity's
     * version and returns to UNVERIFIED: whatever verification the previous definition had
     * earned says nothing about this one (FR-REG-02/03).
     *
     * @throws RegistryValidationException if an image locator's template references do not resolve
     */
    @Transactional
    public String saveLocator(String name, String s1Version, String owner, LocatorDefinition definition) {
        validator.validate(definition, name);
        String json = toJson(definition);
        String type = definition.type() == null ? LocatorType.IMAGE.name() : definition.type().name();
        LocatorEntity entity = locators.findByName(name).orElse(null);
        if (entity == null) {
            entity = new LocatorEntity(UUID.randomUUID().toString(), name, type, s1Version, owner, json);
        } else {
            entity.setDefinitionJson(json);
            entity.setS1Version(s1Version);
            entity.setStatus(LocatorEntity.Status.UNVERIFIED);
        }
        locators.save(entity);
        audit.record(owner, "SAVE_LOCATOR", null, null, "locator=" + name + " type=" + type);
        return entity.getId();
    }

    @Transactional
    public void verifyLocator(String id) {
        locators.findById(id).ifPresent(l -> {
            l.setStatus(LocatorEntity.Status.VERIFIED);
            locators.save(l);
        });
    }

    public List<LocatorEntity> listLocators() {
        return locators.findAllByOrderByUpdatedAtDesc();
    }

    public Optional<LocatorEntity> getLocator(String id) {
        return locators.findById(id);
    }

    /** The stored JSON parsed back into a definition, for callers that act on its fields. */
    public LocatorDefinition readLocatorDefinition(LocatorEntity locator) {
        try {
            return mapper.readValue(locator.getDefinitionJson(), LocatorDefinition.class);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt locator definition for " + locator.getId(), e);
        }
    }

    // ---- helpers ----

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
            throw new IllegalArgumentException("Cannot serialise definition", e);
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            return "";
        }
    }
}
