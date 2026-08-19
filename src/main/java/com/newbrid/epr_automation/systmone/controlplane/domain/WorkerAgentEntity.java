package com.newbrid.epr_automation.systmone.controlplane.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Registered worker/agent health (FR-CP-06, FR-WRK-08). */
@Entity
@Table(name = "worker_agent")
public class WorkerAgentEntity {

    @Id
    private String id;
    private String host;
    private String s1Version;
    private int screenWidth;
    private int screenHeight;
    private String capabilities;
    private String adapters;
    private String status;
    private String currentRunId;
    private Instant registeredAt;
    private Instant lastSeenAt;

    protected WorkerAgentEntity() {}

    public WorkerAgentEntity(String id) {
        this.id = id;
        this.registeredAt = Instant.now();
        this.lastSeenAt = this.registeredAt;
        this.status = "IDLE";
    }

    public String getId() { return id; }
    public String getHost() { return host; }
    public String getS1Version() { return s1Version; }
    public int getScreenWidth() { return screenWidth; }
    public int getScreenHeight() { return screenHeight; }
    public String getCapabilities() { return capabilities; }
    public String getAdapters() { return adapters; }
    public String getStatus() { return status; }
    public String getCurrentRunId() { return currentRunId; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }

    public void setHost(String host) { this.host = host; }
    public void setS1Version(String s1Version) { this.s1Version = s1Version; }
    public void setScreenWidth(int w) { this.screenWidth = w; }
    public void setScreenHeight(int h) { this.screenHeight = h; }
    public void setCapabilities(String c) { this.capabilities = c; }
    public void setAdapters(String a) { this.adapters = a; }
    public void setStatus(String status) { this.status = status; }
    public void setCurrentRunId(String runId) { this.currentRunId = runId; }
    public void setLastSeenAt(Instant t) { this.lastSeenAt = t; }
}
