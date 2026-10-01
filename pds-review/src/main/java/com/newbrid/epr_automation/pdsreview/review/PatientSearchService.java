package com.newbrid.epr_automation.pdsreview.review;

import com.newbrid.epr_automation.pdsreview.audit.AuditEntry;
import com.newbrid.epr_automation.pdsreview.audit.AuditEvent;
import com.newbrid.epr_automation.pdsreview.audit.AuditService;
import com.newbrid.epr_automation.pdsreview.auth.CurrentReviewer;
import com.newbrid.epr_automation.pdsreview.auth.ReviewerIdentity;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItem;
import com.newbrid.epr_automation.pdsreview.domain.SearchKind;
import com.newbrid.epr_automation.pdsreview.domain.SearchOutcome;
import com.newbrid.epr_automation.pdsreview.ods.OdsClient;
import com.newbrid.epr_automation.pdsreview.pds.PdsCandidate;
import com.newbrid.epr_automation.pdsreview.pds.PdsClient;
import com.newbrid.epr_automation.pdsreview.pds.PdsSearchParameters;
import com.newbrid.epr_automation.pdsreview.pds.PdsSearchResult;
import com.newbrid.epr_automation.pdsreview.pds.PostcodeNormaliser;
import com.newbrid.epr_automation.pdsreview.presentation.DataItemMapping;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Runs the searches, and writes down what was asked and what came back.
 *
 * <p>None of the public methods here is transactional, on purpose: a database transaction must
 * not be held open across a call to a national API. What they do instead is the pattern FR-AUD-08
 * asks for — commit the attempt <em>before</em> the call, make the call, then commit the outcome.
 * A search that times out, or a process that dies mid-call, still leaves a record that somebody
 * looked at this patient.
 *
 * <p>The search order is a default the reviewer may depart from (FR-REV-09), so nothing here
 * checks what ran before. What replaces that check is the record: every execution is a row, in
 * order, and the decision points at them (FR-REV-10).
 */
@Service
public class PatientSearchService {

    private final PdsClient pds;
    private final OdsClient ods;
    private final SearchRecordWriter recordWriter;
    private final CurrentReviewer currentReviewer;
    private final AuditService audit;

    public PatientSearchService(PdsClient pds, OdsClient ods, SearchRecordWriter recordWriter,
                                CurrentReviewer currentReviewer, AuditService audit) {
        this.pds = pds;
        this.ods = ods;
        this.recordWriter = recordWriter;
        this.currentReviewer = currentReviewer;
        this.audit = audit;
    }

    /**
     * The automatic search on opening an item: non-fuzzy, built from the patient's own answers,
     * and always with {@code _history=true} — a patient who has married or moved is exactly the
     * one whose current details fail to match (FR-PDS-01, FR-PDS-02).
     */
    public PdsSearchResult nonFuzzy(ReviewItem item, DataItemMapping.Derived derived) {
        PdsSearchParameters parameters = PdsSearchParameters.nonFuzzy(
                derived.familyName(), derived.givenName(), derived.gender(), derived.birthdate(),
                derived.postcode().usable() ? derived.postcode().normalised() : null,
                null, null, true, pds.maxResults());
        return run(item, SearchKind.PDS_NON_FUZZY, parameters, derived.postcode());
    }

    /**
     * The same search narrowed to one practice. {@code general-practitioner} narrows; it does not
     * replace the fields PDS requires, which is why the mandatory combination still applies
     * (FR-ODS-07).
     */
    public PdsSearchResult byGpPractice(ReviewItem item, DataItemMapping.Derived derived,
                                        String practiceOdsCode) {
        PdsSearchParameters parameters = PdsSearchParameters.nonFuzzy(
                derived.familyName(), derived.givenName(), derived.gender(), derived.birthdate(),
                derived.postcode().usable() ? derived.postcode().normalised() : null,
                practiceOdsCode, null, true, pds.maxResults());
        return run(item, SearchKind.PDS_BY_GP_PRACTICE, parameters, derived.postcode());
    }

    /**
     * A fuzzy search with the reviewer's own parameters. The postcode they typed is normalised on
     * the same rules as the automatic search — a reviewer's typing is not held to a stricter
     * standard than the patient's (FR-FUZ-02, FR-PDS-05a).
     */
    public PdsSearchResult fuzzy(ReviewItem item, String family, String given, String gender,
                                 String birthdate, String postcode, boolean history) {
        PostcodeNormaliser.Result normalised = PostcodeNormaliser.normalise(postcode);
        PdsSearchParameters parameters = PdsSearchParameters.fuzzy(
                family, given, gender, birthdate,
                normalised.usable() ? normalised.normalised() : null,
                history, pds.maxResults());
        return run(item, SearchKind.PDS_FUZZY, parameters, normalised);
    }

    /**
     * Finds GP practices. Only organisation search terms cross this boundary — ODS is
     * unauthenticated, and a patient's postcode goes as its outward code alone (FR-ODS-11).
     */
    public OdsClient.PracticeResult findPractices(ReviewItem item, OdsClient.PracticeQuery query) {
        ReviewerIdentity reviewer = currentReviewer.require();
        String description = "ODS /Organization name=" + nullSafe(query.name())
                + " postcode=" + nullSafe(query.postcodeFragment())
                + " town=" + nullSafe(query.town())
                + " activeRoleCode=" + ods.gpPracticeRoleCode();

        AuditEntry started = audit.recordOutsideTransaction(AuditEvent.SEARCH_RUN,
                item.getItemKey(), item.getCorrelationId(), null, description);

        OdsClient.PracticeResult result = ods.findPractices(query,
                reviewer.callContext(item.getCorrelationId()));

        SearchOutcome outcome = result.failed()
                ? SearchOutcome.ERROR
                : (result.practices().isEmpty() ? SearchOutcome.NONE : SearchOutcome.RESULTS);

        recordWriter.write(item, SearchKind.ODS_GP_PRACTICE, description, null,
                outcome, result.practices().size(), null, null, result.elapsedMs(),
                result.problem(), reviewer);

        audit.completeOutsideTransaction(AuditEvent.SEARCH_RUN, item.getItemKey(),
                item.getCorrelationId(), null,
                outcome + ", " + result.practices().size() + " practices"
                        + (result.fromCache() ? " (cached)" : ""),
                started.getId());
        return result;
    }

    /**
     * Retrieves one candidate in full, so a decision can name the version of the record it was
     * made against (FR-PDS-11). Its own audit event: this is a disclosure of one identified
     * patient rather than a search across many.
     */
    public Optional<PdsCandidate> retrieveCandidate(ReviewItem item, String nhsNumber) {
        ReviewerIdentity reviewer = currentReviewer.require();
        AuditEntry started = audit.recordOutsideTransaction(AuditEvent.CANDIDATE_RETRIEVED,
                item.getItemKey(), item.getCorrelationId(), nhsNumber,
                "PDS GET /Patient/" + nhsNumber);

        Optional<PdsCandidate> candidate =
                pds.retrieve(nhsNumber, reviewer.callContext(item.getCorrelationId()));

        audit.completeOutsideTransaction(AuditEvent.CANDIDATE_RETRIEVED, item.getItemKey(),
                item.getCorrelationId(), nhsNumber,
                candidate.map(found -> "Retrieved, version " + found.versionId())
                        .orElse("Not retrieved"),
                started.getId());
        return candidate;
    }

    /** Audit the attempt, make the call, record the outcome. In that order, always. */
    private PdsSearchResult run(ReviewItem item, SearchKind kind, PdsSearchParameters parameters,
                                PostcodeNormaliser.Result postcode) {
        ReviewerIdentity reviewer = currentReviewer.require();
        String description = "PDS /Patient " + parameters.canonical();

        if (!parameters.valid()) {
            // Not a failed search — a search that was never issued, and the difference is the
            // whole of FR-PDS-04. Recorded as such so the trail cannot be read as "PDS was asked
            // and said nothing".
            String problems = String.join("; ", parameters.problems());
            audit.recordOutsideTransaction(AuditEvent.SEARCH_NOT_ATTEMPTED, item.getItemKey(),
                    item.getCorrelationId(), null, problems);
            recordWriter.write(item, kind, description, postcode, SearchOutcome.NOT_ATTEMPTED,
                    0, null, null, 0L, problems, reviewer);
            return PdsSearchResult.notAttempted(problems);
        }

        AuditEntry started = audit.recordOutsideTransaction(AuditEvent.SEARCH_RUN,
                item.getItemKey(), item.getCorrelationId(), null, description);

        PdsSearchResult result = pds.search(parameters,
                reviewer.callContext(item.getCorrelationId()));

        recordWriter.write(item, kind, description, postcode, result.outcome(), result.count(),
                result.topScore(), result.scores(), result.elapsedMs(), result.detail(), reviewer);

        audit.completeOutsideTransaction(AuditEvent.SEARCH_RUN, item.getItemKey(),
                item.getCorrelationId(), null,
                result.outcome() + ", " + result.count() + " candidates"
                        + (result.topScore() == null ? "" : ", top score " + result.topScore())
                        + (result.detail() == null ? "" : " — " + result.detail()),
                started.getId());
        return result;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
