package com.newbrid.epr_automation.systmone.controlplane.domain;

import com.newbrid.epr_automation.systmone.common.model.RunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/** One dispatched bot run and its lifecycle (NFR-REL-02: always ends in a recorded terminal state). */
@Entity
@Table(name = "run", indexes = {
        @Index(name = "idx_run_status", columnList = "status"),
        @Index(name = "idx_run_bot", columnList = "botId")
})
public class RunEntity {

    @Id
    private String id;
    private String botId;
    private String botName;

    @Enumerated(EnumType.STRING)
    private RunStatus status;

    private String capabilityTag;

    @Column(length = 1000000)
    private String inputJson;

    @Column(length = 1000000)
    private String outputsJson;

    private String workerId;

    private String errorType;

    @Column(length = 1000000)
    private String errorMessage;

    @Column(length = 1000000)
    private String errorStack;

    private int attempt;

    private Instant createdAt;
    private Instant dispatchedAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant lastHeartbeatAt;

    protected RunEntity() {}

    public RunEntity(String id, String botId, String botName, String capabilityTag, String inputJson) {
        this.id = id;
        this.botId = botId;
        this.botName = botName;
        this.capabilityTag = capabilityTag;
        this.inputJson = inputJson;
        this.status = RunStatus.PENDING;
        this.attempt = 0;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getBotId() { return botId; }
    public String getBotName() { return botName; }
    public RunStatus getStatus() { return status; }
    public String getCapabilityTag() { return capabilityTag; }
    public String getInputJson() { return inputJson; }
    public String getOutputsJson() { return outputsJson; }
    public String getWorkerId() { return workerId; }
    public String getErrorType() { return errorType; }
    public String getErrorMessage() { return errorMessage; }
    public String getErrorStack() { return errorStack; }
    public int getAttempt() { return attempt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }

    public void setStatus(RunStatus status) { this.status = status; }
    public void setOutputsJson(String outputsJson) { this.outputsJson = outputsJson; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setErrorStack(String errorStack) { this.errorStack = errorStack; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public void setDispatchedAt(Instant t) { this.dispatchedAt = t; }
    public void setStartedAt(Instant t) { this.startedAt = t; }
    public void setFinishedAt(Instant t) { this.finishedAt = t; }
    public void setLastHeartbeatAt(Instant t) { this.lastHeartbeatAt = t; }
}
