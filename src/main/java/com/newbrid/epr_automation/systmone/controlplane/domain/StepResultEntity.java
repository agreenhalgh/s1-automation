package com.newbrid.epr_automation.systmone.controlplane.domain;

import com.newbrid.epr_automation.systmone.common.model.StepStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A single step's recorded outcome. Unique on (runId, stepIndex) so the results endpoint
 * can upsert idempotently under redelivery (FR-CP-02a).
 */
@Entity
@Table(name = "step_result", uniqueConstraints =
        @UniqueConstraint(name = "uq_step_run_index", columnNames = {"runId", "stepIndex"}))
public class StepResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String runId;
    private int stepIndex;
    private String name;

    @Enumerated(EnumType.STRING)
    private StepStatus status;

    private String resolvedLocator;
    private long durationMs;

    @Column(length = 1000000)
    private String output;

    private String errorType;

    @Column(length = 1000000)
    private String errorMessage;

    @Column(length = 1000000)
    private String errorStack;

    private String screenshotRef;
    private Instant ts;

    protected StepResultEntity() {}

    public StepResultEntity(String runId, int stepIndex) {
        this.runId = runId;
        this.stepIndex = stepIndex;
        this.ts = Instant.now();
    }

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public int getStepIndex() { return stepIndex; }
    public String getName() { return name; }
    public StepStatus getStatus() { return status; }
    public String getResolvedLocator() { return resolvedLocator; }
    public long getDurationMs() { return durationMs; }
    public String getOutput() { return output; }
    public String getErrorType() { return errorType; }
    public String getErrorMessage() { return errorMessage; }
    public String getErrorStack() { return errorStack; }
    public String getScreenshotRef() { return screenshotRef; }
    public Instant getTs() { return ts; }

    public void setName(String name) { this.name = name; }
    public void setStatus(StepStatus status) { this.status = status; }
    public void setResolvedLocator(String resolvedLocator) { this.resolvedLocator = resolvedLocator; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public void setOutput(String output) { this.output = output; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setErrorStack(String errorStack) { this.errorStack = errorStack; }
    public void setScreenshotRef(String screenshotRef) { this.screenshotRef = screenshotRef; }
    public void setTs(Instant ts) { this.ts = ts; }
}
