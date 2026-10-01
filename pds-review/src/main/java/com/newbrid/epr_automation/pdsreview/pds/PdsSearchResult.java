package com.newbrid.epr_automation.pdsreview.pds;

import com.newbrid.epr_automation.pdsreview.domain.SearchOutcome;

import java.util.List;
import java.util.stream.Collectors;

/**
 * What came back. The {@link SearchOutcome} is the load-bearing field: "no matches", "too many
 * matches" and "PDS did not answer" are three different answers and this type refuses to let a
 * caller treat them as one (FR-PDS-09, FR-PDS-12).
 *
 * <p>That matters because of what a reviewer does next. A nil return supports "not on PDS"; an
 * error supports nothing at all, and recording an accept-unmatched decision on the back of one
 * would be asserting something about a patient PDS was never asked about (R10).
 *
 * @param detail why, for the outcomes that need explaining. Shown to the reviewer, not just logged
 */
public record PdsSearchResult(
        SearchOutcome outcome,
        List<PdsCandidate> candidates,
        String detail,
        long elapsedMs) {

    public static PdsSearchResult results(List<PdsCandidate> candidates, long elapsedMs) {
        return candidates.isEmpty()
                ? new PdsSearchResult(SearchOutcome.NONE, List.of(), null, elapsedMs)
                : new PdsSearchResult(SearchOutcome.RESULTS, List.copyOf(candidates), null, elapsedMs);
    }

    public static PdsSearchResult tooMany(long elapsedMs) {
        return new PdsSearchResult(SearchOutcome.TOO_MANY, List.of(),
                "More matches than PDS will return, so it returned none of them. Narrow the search.",
                elapsedMs);
    }

    public static PdsSearchResult error(String detail, long elapsedMs) {
        return new PdsSearchResult(SearchOutcome.ERROR, List.of(), detail, elapsedMs);
    }

    public static PdsSearchResult notAttempted(String detail) {
        return new PdsSearchResult(SearchOutcome.NOT_ATTEMPTED, List.of(), detail, 0L);
    }

    /** Whether a nil result here means "PDS holds no such patient". Only true if PDS answered. */
    public boolean establishesAbsence() {
        return outcome == SearchOutcome.NONE;
    }

    public int count() {
        return candidates.size();
    }

    public Double topScore() {
        return candidates.stream()
                .map(PdsCandidate::score)
                .filter(java.util.Objects::nonNull)
                .max(Double::compareTo)
                .orElse(null);
    }

    /** Scores highest first, for the audit trail. */
    public String scores() {
        return candidates.stream()
                .map(candidate -> candidate.score() == null ? "?" : String.valueOf(candidate.score()))
                .collect(Collectors.joining(","));
    }
}
