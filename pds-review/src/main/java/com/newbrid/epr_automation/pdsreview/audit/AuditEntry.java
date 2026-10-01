package com.newbrid.epr_automation.pdsreview.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per thing somebody did — append-only, never updated, never deleted through any path in
 * this application (FR-AUD-05). There is no setter here and no update method: the only operation
 * the repository exposes is an insert.
 *
 * <p><b>This table holds patient identifiers on purpose.</b> The gateway hashes the NHS number out
 * of its own trail; that option is not open here. The question this table exists to answer is
 * "who looked at this patient, and what did they search for?", and the search parameters *are*
 * the patient's identifiers. Recording a pseudonym would make the trail unable to answer the
 * question it was built for. So it is patient data, access-controlled to the audit role and
 * encrypted at rest, rather than data made safe by hashing (§5.4).
 *
 * <p>Every entry carries the actor's identity <em>and the role they had selected</em>. A search
 * made under one role must not be attributable to another (FR-AUTH-05), and a person who holds
 * both the review and audit activities exercises one at a time (FR-AUTH-07a).
 */
@Entity
@Table(name = "audit_entry", indexes = {
        @Index(name = "idx_audit_at", columnList = "at"),
        @Index(name = "idx_audit_actor", columnList = "actor, at"),
        @Index(name = "idx_audit_item", columnList = "itemKey, at"),
        @Index(name = "idx_audit_event", columnList = "event, at"),
        @Index(name = "idx_audit_nhs", columnList = "nhsNumber")
})
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant at;

    /** Who. A named CIS2 identity, or {@code gateway} for the intake route, which has no user. */
    @Column(nullable = false, length = 128)
    private String actor;

    /** The URID of the role they had selected. */
    @Column(length = 64)
    private String actorRole;

    /** Their organisation's ODS code, from the token. */
    @Column(length = 32)
    private String actorOrg;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AuditEvent event;

    /** Which item, where there is one. */
    @Column(length = 128)
    private String itemKey;

    /** Shared with the presentation's rows in the gateway's trail (FR-AUD-04). */
    @Column(length = 128)
    private String correlationId;

    /** The patient, where the event concerns an identified one. */
    @Column(length = 20)
    private String nhsNumber;

    /** What was done — parameters sent, decision recorded, filter in force. */
    @Column(length = 4000)
    private String detail;

    /** Set when the event is the completion of something started earlier (FR-AUD-08). */
    private Long relatedId;

    protected AuditEntry() {}

    AuditEntry(Instant at, String actor, String actorRole, String actorOrg, AuditEvent event,
               String itemKey, String correlationId, String nhsNumber, String detail,
               Long relatedId) {
        this.at = at;
        this.actor = actor;
        this.actorRole = actorRole;
        this.actorOrg = actorOrg;
        this.event = event;
        this.itemKey = itemKey;
        this.correlationId = correlationId;
        this.nhsNumber = nhsNumber;
        this.detail = detail;
        this.relatedId = relatedId;
    }

    public Long getId() { return id; }
    public Instant getAt() { return at; }
    public String getActor() { return actor; }
    public String getActorRole() { return actorRole; }
    public String getActorOrg() { return actorOrg; }
    public AuditEvent getEvent() { return event; }
    public String getItemKey() { return itemKey; }
    public String getCorrelationId() { return correlationId; }
    public String getNhsNumber() { return nhsNumber; }
    public String getDetail() { return detail; }
    public Long getRelatedId() { return relatedId; }
}
