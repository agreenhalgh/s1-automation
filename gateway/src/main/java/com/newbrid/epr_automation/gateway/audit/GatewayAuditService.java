package com.newbrid.epr_automation.gateway.audit;

import com.newbrid.epr_automation.gateway.api.RunOutcome;
import com.newbrid.epr_automation.gateway.api.RunSubmission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Writes the gateway's trail. Every method is one append; nothing here updates or deletes.
 *
 * <p>Each write runs in its own transaction ({@code REQUIRES_NEW}). That is the whole reason this
 * is a service rather than a repository call in the route: a request that ends in a rollback —
 * a control plane that refused the dispatch, a validation failure thrown mid-route — must still
 * leave its audit rows behind. An audit trail that disappears exactly when something went wrong
 * is worse than none, because it reads as "nothing happened".
 */
@Service
public class GatewayAuditService {

    private final GatewayAuditRepository repo;
    private final SubjectPseudonymiser pseudonymiser;

    public GatewayAuditService(GatewayAuditRepository repo, SubjectPseudonymiser pseudonymiser) {
        this.repo = repo;
        this.pseudonymiser = pseudonymiser;
    }

    /** A request arrived and parsed. Written before validation, so a rejection has a parent row. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void received(String correlationId, String source, RunSubmission submission) {
        GatewayAuditEntity e = new GatewayAuditEntity(
                correlationId, submission.requestId(), source, "RECEIVED");
        e.setBotId(submission.bot());
        e.setCapabilityTag(submission.pool());
        e.setSubjectRef(subjectRef(submission));
        e.setParameterKeys(parameterKeys(submission));
        repo.save(e);
    }

    /**
     * A request that never became a run: no token, unparseable body, failed validation. Recorded
     * with the reason, because "who keeps sending us rubbish, and what kind" is an integration
     * question that only this trail can answer.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rejected(String correlationId, String clientRequestId, String source,
                         int httpStatus, String reason, long elapsedMs) {
        GatewayAuditEntity e = new GatewayAuditEntity(correlationId, clientRequestId, source, "REJECTED");
        e.setHttpStatus(httpStatus);
        e.setDetail(truncate(reason));
        e.setElapsedMs(elapsedMs);
        repo.save(e);
    }

    /**
     * A submitted PDF was written to the document store. Recorded before dispatch, which is the
     * order things happen in: the run needs the path, so the file exists first.
     *
     * <p>That ordering means a dispatch that then fails leaves a document on disk with no run
     * against it. The file is kept rather than deleted — throwing away a clinical document
     * because a downstream call failed is the worse of the two mistakes — and this row plus the
     * REJECTED row after it are what make the orphan findable.
     *
     * <p>The submitter's id is recorded in the clear, unlike a patient identifier. It is the
     * principal: "who did this" is the first thing an audit trail exists to answer (NFR-SEC-05),
     * and it is already the name of the file on disk.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void documentStored(String correlationId, String clientRequestId, String botId,
                               String userId, String fileName, String path, int bytes,
                               String sha256, long elapsedMs) {
        GatewayAuditEntity e = new GatewayAuditEntity(
                correlationId, clientRequestId, null, "DOCUMENT_STORED");
        e.setBotId(botId);
        e.setElapsedMs(elapsedMs);
        e.setDetail(truncate("user=" + userId + " file=" + fileName + " path=" + path
                + " bytes=" + bytes + " sha256=" + sha256));
        repo.save(e);
    }

    /** The control plane accepted the transformed request and created a run. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatched(String correlationId, String clientRequestId, String botId,
                           String capabilityTag, String runId, long elapsedMs) {
        GatewayAuditEntity e = new GatewayAuditEntity(correlationId, clientRequestId, null, "DISPATCHED");
        e.setBotId(botId);
        e.setCapabilityTag(capabilityTag);
        e.setRunId(runId);
        e.setElapsedMs(elapsedMs);
        repo.save(e);
    }

    /**
     * The end of the request, whatever that end was. The stage is the outcome name, so the trail
     * distinguishes a run that timed out from a gateway that stopped waiting — the row for the
     * latter is the only record that a run was left in flight.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settled(String correlationId, String clientRequestId, String botId, String runId,
                        RunOutcome outcome, int httpStatus, long elapsedMs, String detail) {
        GatewayAuditEntity e = new GatewayAuditEntity(
                correlationId, clientRequestId, null, outcome.name());
        e.setBotId(botId);
        e.setRunId(runId);
        e.setHttpStatus(httpStatus);
        e.setElapsedMs(elapsedMs);
        e.setDetail(truncate(detail));
        repo.save(e);
    }

    /** Every row for one request, oldest first — the story of what happened to it. */
    @Transactional(readOnly = true)
    public List<GatewayAuditEntity> trail(String correlationId) {
        return repo.findByCorrelationIdOrderByTsAsc(correlationId);
    }

    private String subjectRef(RunSubmission submission) {
        RunSubmission.Subject subject = submission.subject();
        if (subject == null || subject.isEmpty()) {
            return null;
        }
        // NHS number first: it is the identifier that follows a patient between systems, so a
        // reference built on it is the one that can be correlated. A local id is the fallback.
        String reference = pseudonymiser.reference("nhs", subject.nhsNumber());
        return reference != null ? reference : pseudonymiser.reference("local", subject.localId());
    }

    /**
     * Keys only. A parameter value on a clinical run can be a name, an address fragment or a
     * clinical code; the key is what the bot definition calls it and carries no patient data.
     */
    private static String parameterKeys(RunSubmission submission) {
        var keys = submission.parametersOrEmpty().keySet();
        if (keys.isEmpty()) {
            return null;
        }
        return truncate(keys.stream().sorted().collect(Collectors.joining(",")), 2000);
    }

    private static String truncate(String s) {
        return truncate(s, 4000);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
