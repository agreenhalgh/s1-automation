package com.newbrid.epr_automation.systmone.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Append-only audit record (NFR-SEC-05): who did what, when, against which run/bot.
 * Never updated — clinical-context references are stored by id, not by copying PID.
 */
@Entity
@Table(name = "audit_log")
public class AuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant ts;
    private String principal;
    private String action;
    private String botId;
    private String runId;

    @Column(length = 1000000)
    private String detail;

    protected AuditEntity() {}

    public AuditEntity(String principal, String action, String botId, String runId, String detail) {
        this.ts = Instant.now();
        this.principal = principal;
        this.action = action;
        this.botId = botId;
        this.runId = runId;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public Instant getTs() { return ts; }
    public String getPrincipal() { return principal; }
    public String getAction() { return action; }
    public String getBotId() { return botId; }
    public String getRunId() { return runId; }
    public String getDetail() { return detail; }
}
