package com.newbrid.epr_automation.systmone.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    /**
     * PNG bytes, held inline in the row.
     *
     * <p>Deliberately not {@code @Lob}: on PostgreSQL that maps to {@code oid}, which keeps
     * the bytes in {@code pg_largeobject} and leaves only a reference here. Large objects are
     * not removed when their row is deleted — every replaced template would leak until
     * someone ran {@code vacuumlo} — and they can only be read inside a transaction, so
     * serving an image outside one fails at run time. On H2 {@code @Lob} behaves, which is
     * exactly why this needs saying: the tests would not catch it.</p>
     *
     * <p>The length is for databases that need one; PostgreSQL ignores it for {@code bytea}.
     * Templates are small crops, so 16 MB is far more headroom than any real one needs.</p>
     */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(length = 16 * 1024 * 1024)
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
