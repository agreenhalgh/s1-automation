package com.newbrid.epr_automation.gateway.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per stage a request passes through — append-only, never updated, matching the control
 * plane's own trail (NFR-SEC-05). A request that is rejected at the door leaves a RECEIVED and a
 * REJECTED row and nothing else; one that runs leaves RECEIVED, DISPATCHED and a terminal row.
 *
 * <p>Why the gateway keeps its own trail at all, rather than relying on the control plane's: a
 * request that fails authentication or validation never becomes a run, so the control plane never
 * hears of it. Those are precisely the events an integration owner needs — who is calling with a
 * bad token, which caller keeps sending a malformed NHS number — and they exist nowhere else.
 *
 * <p><b>No patient identifiers are stored here.</b> The NHS number is passed onward to the run,
 * because the bot needs it to find the record, but what lands in this table is
 * {@code subjectRef}: a keyed HMAC of it (see {@link SubjectPseudonymiser}). The same patient
 * produces the same reference, so a trail can be followed, while the table itself is not a
 * register of who was looked up. Parameter <em>keys</em> are recorded and values are not, for the
 * same reason — a value can carry clinical context, a key names it.
 */
@Entity
@Table(name = "gateway_request_audit", indexes = {
        @Index(name = "idx_gateway_audit_correlation", columnList = "correlationId"),
        @Index(name = "idx_gateway_audit_ts", columnList = "ts"),
        @Index(name = "idx_gateway_audit_run", columnList = "runId")
})
public class GatewayAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant ts;

    /** The gateway's id for this request, the same on every row belonging to it. */
    private String correlationId;

    /** The caller's own id, if they sent one. Their reference for reconciling logs with ours. */
    private String clientRequestId;

    /** Where the request came from, as far as the gateway can tell. */
    private String source;

    /** RECEIVED / REJECTED / DISPATCHED / one of the terminal outcomes. */
    private String stage;

    private String botId;
    private String capabilityTag;

    /** Keyed HMAC of the patient identifier — never the identifier. */
    private String subjectRef;

    /** Names of the parameters supplied, comma-separated. Values are deliberately absent. */
    @Column(length = 2000)
    private String parameterKeys;

    /** The control plane's run id, once there is one. */
    private String runId;

    /** HTTP status the caller was given, on the rows that answer a caller. */
    private Integer httpStatus;

    /** Milliseconds from arrival to this stage. */
    private Long elapsedMs;

    @Column(length = 4000)
    private String detail;

    protected GatewayAuditEntity() {}

    GatewayAuditEntity(String correlationId, String clientRequestId, String source, String stage) {
        this.ts = Instant.now();
        this.correlationId = correlationId;
        this.clientRequestId = clientRequestId;
        this.source = source;
        this.stage = stage;
    }

    public Long getId() { return id; }
    public Instant getTs() { return ts; }
    public String getCorrelationId() { return correlationId; }
    public String getClientRequestId() { return clientRequestId; }
    public String getSource() { return source; }
    public String getStage() { return stage; }
    public String getBotId() { return botId; }
    public String getCapabilityTag() { return capabilityTag; }
    public String getSubjectRef() { return subjectRef; }
    public String getParameterKeys() { return parameterKeys; }
    public String getRunId() { return runId; }
    public Integer getHttpStatus() { return httpStatus; }
    public Long getElapsedMs() { return elapsedMs; }
    public String getDetail() { return detail; }

    // Package-private setters: rows are assembled by GatewayAuditService and by nothing else,
    // which is what keeps "append-only" a property of the code rather than a convention.
    void setBotId(String v) { this.botId = v; }
    void setCapabilityTag(String v) { this.capabilityTag = v; }
    void setSubjectRef(String v) { this.subjectRef = v; }
    void setParameterKeys(String v) { this.parameterKeys = v; }
    void setRunId(String v) { this.runId = v; }
    void setHttpStatus(Integer v) { this.httpStatus = v; }
    void setElapsedMs(Long v) { this.elapsedMs = v; }
    void setDetail(String v) { this.detail = v; }
}
