package com.newbrid.epr_automation.systmone.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A screen-recognition template (partial screenshot) in the locator registry
 * (FR-SR-08, FR-REG-01/04). Image bytes are stored with a checksum.
 */
@Entity
@Table(name = "template")
public class TemplateEntity {

    @Id
    private String id;
    private String name;
    private String s1Version;
    private double threshold;
    private String screenContext;
    private String createdBy;
    private String checksum;

    @Lob
    private byte[] data;

    private Instant createdAt;

    protected TemplateEntity() {}

    public TemplateEntity(String id, String name, String s1Version, double threshold,
                          String screenContext, String createdBy, String checksum, byte[] data) {
        this.id = id;
        this.name = name;
        this.s1Version = s1Version;
        this.threshold = threshold;
        this.screenContext = screenContext;
        this.createdBy = createdBy;
        this.checksum = checksum;
        this.data = data;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getS1Version() { return s1Version; }
    public double getThreshold() { return threshold; }
    public String getScreenContext() { return screenContext; }
    public String getCreatedBy() { return createdBy; }
    public String getChecksum() { return checksum; }
    public byte[] getData() { return data; }
    public Instant getCreatedAt() { return createdAt; }
}
