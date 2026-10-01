package com.newbrid.epr_automation.pdsreview.audit;

import com.newbrid.epr_automation.pdsreview.auth.CurrentReviewer;
import com.newbrid.epr_automation.pdsreview.auth.ReviewerIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Writes the trail.
 *
 * <p>Two properties of this class are requirements rather than style.
 *
 * <p><b>It writes in the caller's transaction</b> (the default propagation), so an action cannot
 * succeed unaudited: if the audit insert fails, the thing being audited rolls back with it
 * (FR-AUD-08). The one exception is {@link #recordOutsideTransaction}, for the outbound calls of
 * FR-AUD-08's second sentence, where an attempt has to be on record even if the call never
 * returns.
 *
 * <p><b>It has no update and no delete.</b> Neither does the repository, and neither does the
 * entity — {@link AuditEntry} exposes no setters. Append-only is enforced by there being no code
 * that could do otherwise, rather than by a convention someone has to remember (FR-AUD-05).
 */
@Service
public class AuditService {

    /** The actor on the one route that has no user: the gateway's intake POST (NFR-SEC-01a). */
    public static final String SYSTEM_ACTOR_GATEWAY = "gateway";

    /** The actor for work nobody triggered — the lock sweeper, the post-back dispatcher. */
    public static final String SYSTEM_ACTOR_SCHEDULER = "scheduler";

    private final AuditRepository repository;
    private final CurrentReviewer currentReviewer;
    private final Clock clock;

    public AuditService(AuditRepository repository, CurrentReviewer currentReviewer, Clock clock) {
        this.repository = repository;
        this.currentReviewer = currentReviewer;
        this.clock = clock;
    }

    /** An event by the signed-in reviewer, under the role they selected. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEntry record(AuditEvent event, String itemKey, String correlationId,
                             String nhsNumber, String detail) {
        Optional<ReviewerIdentity> reviewer = currentReviewer.current();
        return write(reviewer.map(ReviewerIdentity::userId).orElse("unauthenticated"),
                reviewer.map(ReviewerIdentity::urid).orElse(null),
                reviewer.map(ReviewerIdentity::organisation).orElse(null),
                event, itemKey, correlationId, nhsNumber, detail, null);
    }

    /** An event by something other than a person — the gateway, or a scheduled job. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEntry recordSystem(String actor, AuditEvent event, String itemKey,
                                   String correlationId, String detail) {
        return write(actor, null, null, event, itemKey, correlationId, null, detail, null);
    }

    /**
     * An entry that must survive the failure of whatever it describes.
     *
     * <p>Used for outbound calls: the attempt is written and committed before PDS is asked, so a
     * search that times out, or a process that dies mid-call, still leaves a record that somebody
     * looked. An audit trail that only records successful disclosures is not an audit trail.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEntry recordOutsideTransaction(AuditEvent event, String itemKey,
                                               String correlationId, String nhsNumber,
                                               String detail) {
        Optional<ReviewerIdentity> reviewer = currentReviewer.current();
        return write(reviewer.map(ReviewerIdentity::userId).orElse("unauthenticated"),
                reviewer.map(ReviewerIdentity::urid).orElse(null),
                reviewer.map(ReviewerIdentity::organisation).orElse(null),
                event, itemKey, correlationId, nhsNumber, detail, null);
    }

    /** Completes an entry written before an outbound call, by pointing back at it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEntry completeOutsideTransaction(AuditEvent event, String itemKey,
                                                 String correlationId, String nhsNumber,
                                                 String detail, Long relatedId) {
        Optional<ReviewerIdentity> reviewer = currentReviewer.current();
        return write(reviewer.map(ReviewerIdentity::userId).orElse("unauthenticated"),
                reviewer.map(ReviewerIdentity::urid).orElse(null),
                reviewer.map(ReviewerIdentity::organisation).orElse(null),
                event, itemKey, correlationId, nhsNumber, detail, relatedId);
    }

    private AuditEntry write(String actor, String role, String organisation, AuditEvent event,
                             String itemKey, String correlationId, String nhsNumber,
                             String detail, Long relatedId) {
        return repository.save(new AuditEntry(Instant.now(clock), actor, role, organisation,
                event, itemKey, correlationId, nhsNumber, truncate(detail), relatedId));
    }

    /** The column is generous but finite, and a truncated entry beats a failed insert. */
    private static String truncate(String detail) {
        if (detail == null || detail.length() <= 4000) {
            return detail;
        }
        return detail.substring(0, 3997) + "...";
    }
}
