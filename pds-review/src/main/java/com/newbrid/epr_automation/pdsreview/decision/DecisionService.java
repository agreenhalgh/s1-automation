package com.newbrid.epr_automation.pdsreview.decision;

import com.newbrid.epr_automation.pdsreview.domain.Decision;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItem;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItemRepository;
import com.newbrid.epr_automation.pdsreview.pds.PdsCandidate;
import com.newbrid.epr_automation.pdsreview.review.PatientSearchService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Records the one terminal decision an item ever gets.
 *
 * <p>The order of operations is the requirement. Whatever else happens, the decision is
 * <b>written down before anything is sent</b> (FR-DEC-07) — the post-back is a row committed in
 * the same transaction, not a call made hopefully and recorded afterwards. A process that dies
 * here loses nothing: the dispatcher finds the pending row and tries again.
 *
 * <p>For a MATCHED decision the candidate is <b>re-read from PDS</b> rather than taken from the
 * form. A hidden field carrying a patient record is a hidden field somebody can edit, and what
 * this decision does is register a human being against a clinical record. Re-reading also
 * captures the version the decision was made against (FR-PDS-11). If PDS cannot be reached the
 * match is refused: a match nobody can verify is not a match, and the reviewer can try again or
 * record a different outcome.
 */
@Service
public class DecisionService {

    /** Whether it was recorded, and if not, what the reviewer needs to fix. */
    public record Result(boolean recorded, List<String> problems) {

        public static Result ok() {
            return new Result(true, List.of());
        }

        public static Result refused(String problem) {
            return new Result(false, List.of(problem));
        }

        public static Result refused(List<String> problems) {
            return new Result(false, List.copyOf(problems));
        }
    }

    private final ReviewItemRepository items;
    private final PatientSearchService searches;
    private final DecisionWriter writer;

    public DecisionService(ReviewItemRepository items, PatientSearchService searches,
                           DecisionWriter writer) {
        this.items = items;
        this.searches = searches;
        this.writer = writer;
    }

    public Result record(Long itemId, DecisionRequest request) {
        if (!request.valid()) {
            return Result.refused(request.problems());
        }

        PdsCandidate candidate = null;
        if (request.decision() == Decision.MATCHED) {
            ReviewItem item = items.findById(itemId).orElse(null);
            if (item == null) {
                return Result.refused("That review item no longer exists");
            }
            // Outside any transaction: this is a call to a national API, and holding a row lock
            // across it would put PDS's latency into the database's contention.
            Optional<PdsCandidate> found = searches.retrieveCandidate(item, request.nhsNumber());
            if (found.isEmpty()) {
                return Result.refused("The selected patient could not be read back from PDS, so "
                        + "the match has not been recorded. Try again, or record a different "
                        + "outcome if PDS is unavailable.");
            }
            candidate = found.get();
        }
        return writer.commit(itemId, request, candidate);
    }
}
