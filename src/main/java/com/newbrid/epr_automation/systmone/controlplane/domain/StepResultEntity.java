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
 * One <em>execution</em> of a step. Unique on (runId, executionSeq) so the results endpoint can
 * upsert idempotently under redelivery (FR-CP-02a).
 *
 * <p>Deliberately not unique on (runId, stepIndex): a conditional flow can send the run back to a
 * step it has already done, and both visits are part of what happened. Keying on the position
 * meant the second visit overwrote the first, so a loop that took four passes to find a row left
 * one row behind and no way to tell it had looped at all.</p>
 *
 * <p>The column is {@code execution_seq} rather than {@code sequence} because that word is a
 * keyword in enough dialects to be worth stepping around, and this table is created by
 * {@code ddl-auto} against both PostgreSQL and H2.</p>
 */
@Entity
@Table(name = "step_result", uniqueConstraints =
        @UniqueConstraint(name = "uq_step_run_sequence", columnNames = {"runId", "executionSeq"}))
public class StepResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String runId;

    /** Which execution within the run this is, counting from 1. */
    @Column(name = "execution_seq", nullable = false)
    private int executionSeq;

    /** Which step of the definition ran — its position, repeated across visits. */
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

    public StepResultEntity(String runId, int executionSeq, int stepIndex) {
        this.runId = runId;
        this.executionSeq = executionSeq;
        this.stepIndex = stepIndex;
        this.ts = Instant.now();
    }

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public int getExecutionSeq() { return executionSeq; }
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

    public void setStepIndex(int stepIndex) { this.stepIndex = stepIndex; }
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
