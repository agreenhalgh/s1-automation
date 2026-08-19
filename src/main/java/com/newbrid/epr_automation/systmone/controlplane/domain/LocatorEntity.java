package com.newbrid.epr_automation.systmone.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A named, versioned locator definition in the registry (FR-REG-01/02/03). Status tracks
 * verification against a SystmOne release.
 */
@Entity
@Table(name = "locator")
public class LocatorEntity {

    public enum Status { VERIFIED, UNVERIFIED }

    @Id
    private String id;
    private String name;
    private String type;      // JAB | IMAGE
    private String s1Version;
    private String owner;
    private int version;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;

    @Column(length = 1000000)
    private String definitionJson;

    private Instant createdAt;
    private Instant updatedAt;

    protected LocatorEntity() {}

    public LocatorEntity(String id, String name, String type, String s1Version, String owner,
                         String definitionJson) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.s1Version = s1Version;
        this.owner = owner;
        this.definitionJson = definitionJson;
        this.version = 1;
        this.status = Status.UNVERIFIED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getType() { return type; }
    public String getS1Version() { return s1Version; }
    public String getOwner() { return owner; }
    public int getVersion() { return version; }
    public Status getStatus() { return status; }
    public String getDefinitionJson() { return definitionJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setStatus(Status status) { this.status = status; this.updatedAt = Instant.now(); }
    public void setDefinitionJson(String json) { this.definitionJson = json; this.version++; this.updatedAt = Instant.now(); }
    public void setS1Version(String s1Version) { this.s1Version = s1Version; }
}
