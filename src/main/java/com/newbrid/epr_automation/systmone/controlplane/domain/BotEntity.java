package com.newbrid.epr_automation.systmone.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/** A registered bot and its JSON-serialised {@link com.newbrid.epr_automation.systmone.common.dto.BotDefinition} (FR-CP-08). */
@Entity
@Table(name = "bot")
public class BotEntity {

    @Id
    private String id;
    private String name;
    private int version;
    private String s1Version;

    @Column(length = 1000000)
    private String definitionJson;

    private Instant createdAt;
    private Instant updatedAt;

    protected BotEntity() {}

    public BotEntity(String id, String name, int version, String s1Version, String definitionJson) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.s1Version = s1Version;
        this.definitionJson = definitionJson;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public int getVersion() { return version; }
    public String getS1Version() { return s1Version; }
    public String getDefinitionJson() { return definitionJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setVersion(int version) { this.version = version; }
    public void setS1Version(String s1Version) { this.s1Version = s1Version; }
    public void setDefinitionJson(String definitionJson) { this.definitionJson = definitionJson; }
    public void touch() { this.updatedAt = Instant.now(); }
}
