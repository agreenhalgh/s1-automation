package com.newbrid.epr_automation.pdsreview.review;

import com.newbrid.epr_automation.pdsreview.auth.ReviewerIdentity;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItem;
import com.newbrid.epr_automation.pdsreview.domain.ReviewSession;
import com.newbrid.epr_automation.pdsreview.domain.ReviewSessionRepository;
import com.newbrid.epr_automation.pdsreview.domain.SearchKind;
import com.newbrid.epr_automation.pdsreview.domain.SearchOutcome;
import com.newbrid.epr_automation.pdsreview.domain.SearchRecord;
import com.newbrid.epr_automation.pdsreview.domain.SearchRecordRepository;
import com.newbrid.epr_automation.pdsreview.pds.PostcodeNormaliser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Commits one search record, in its own transaction.
 *
 * <p>A separate bean rather than a method on {@link PatientSearchService} because Spring's
 * transaction proxying does not intercept a call a class makes to itself — a {@code @Transactional}
 * method invoked through {@code this} runs with no transaction at all, silently. Here the boundary
 * is real, and {@code REQUIRES_NEW} makes it independent of whatever the request is doing: the
 * fact that a patient was searched for should survive the request that searched failing
 * afterwards (FR-AUD-08).
 */
@Service
public class SearchRecordWriter {

    private final SearchRecordRepository records;
    private final ReviewSessionRepository sessions;
    private final Clock clock;

    public SearchRecordWriter(SearchRecordRepository records, ReviewSessionRepository sessions,
                              Clock clock) {
        this.records = records;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * @param postcode the normalisation attempt, or null where no postcode was in play. Both the
     *                 submitted and the sent form are stored (FR-AUD-02)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SearchRecord write(ReviewItem item, SearchKind kind, String description,
                              PostcodeNormaliser.Result postcode, SearchOutcome outcome,
                              int count, Double topScore, String scores, long elapsedMs,
                              String detail, ReviewerIdentity reviewer) {

        Long sessionId = sessions
                .findFirstByItemIdAndEndedAtIsNullOrderByStartedAtDesc(item.getId())
                .map(ReviewSession::getId)
                .orElse(null);

        SearchRecord record = SearchRecord.of(item.getId(), sessionId, kind, description,
                reviewer.userId(), reviewer.urid(), Instant.now(clock));
        if (postcode != null) {
            record.postcode(postcode.submitted(), postcode.usable() ? postcode.normalised() : null);
        }
        record.completed(outcome, count, topScore, scores, elapsedMs, detail);
        return records.save(record);
    }
}
