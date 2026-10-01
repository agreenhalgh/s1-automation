package com.newbrid.epr_automation.pdsreview.review;

import com.newbrid.epr_automation.pdsreview.domain.ReviewItem;
import com.newbrid.epr_automation.pdsreview.domain.ReviewSession;
import com.newbrid.epr_automation.pdsreview.domain.SearchKind;
import com.newbrid.epr_automation.pdsreview.domain.SearchOutcome;
import com.newbrid.epr_automation.pdsreview.domain.SearchRecord;
import com.newbrid.epr_automation.pdsreview.presentation.DataItemMapping;
import com.newbrid.epr_automation.pdsreview.presentation.PatientAnswer;
import com.newbrid.epr_automation.pdsreview.presentation.PresentationView;
import com.newbrid.epr_automation.pdsreview.domain.UnmatchedReason;

import java.util.List;

/**
 * Everything the review screen needs, assembled once.
 *
 * @param sessions        earlier sittings, newest first, so this reviewer can see what the last
 *                        one did rather than repeating it (FR-REV-05)
 * @param searches        every search run against this item, in the order it was run — the path
 *                        the reviewer took, now that the order is only a default (FR-REV-10)
 * @param earlierDecisions decided items for the same stated name and date of birth: a probable
 *                        repeat presentation, shown with what was decided last time (FR-INT-07)
 */
public record ReviewView(ReviewItem item,
                         PresentationView presentation,
                         DataItemMapping.Derived derived,
                         List<ReviewSession> sessions,
                         List<SearchRecord> searches,
                         List<ReviewItem> earlierDecisions) {

    /** The patient's own answers, which the screen must label as unverified (FR-REV-03). */
    public List<PatientAnswer> answers() {
        return presentation.answers().stream().filter(PatientAnswer::answered).toList();
    }

    /**
     * What a nil search result would mean here, in a sentence. Shown before the reviewer starts,
     * because it changes the weight of everything they are about to do (§2.2, FR-REV-03).
     */
    public String unmatchedExplanation() {
        UnmatchedReason reason = item.getUnmatchedReason();
        return switch (reason) {
            case PDS_LOOKUP_UNSUCCESSFUL -> "PDS was asked and matched nobody. The patient's own "
                    + "details may be wrong or incomplete, or they may genuinely have no PDS record.";
            case PDS_UNAVAILABLE -> "PDS was not reachable when the patient was triaged, so "
                    + "nothing has been established either way. A search here may well match.";
            case NOT_STATED -> "The sender recorded no PDS lookup at all, so nothing is known "
                    + "about whether PDS was ever asked about this patient.";
        };
    }

    /** Whether a non-fuzzy search can be issued at all (FR-PDS-04). */
    public boolean canSearchNonFuzzy() {
        return derived.canSearchNonFuzzy();
    }

    /** Which required fields could not be derived, named for the reviewer rather than logged. */
    public List<String> missingRequiredFields() {
        return derived.missingRequired().stream()
                .map(field -> switch (field) {
                    case "family" -> "family name";
                    case "gender" -> "gender";
                    case "birthdate" -> "date of birth";
                    default -> field;
                })
                .toList();
    }

    public boolean anySearchRun() {
        return !searches.isEmpty();
    }

    /** Whether PDS has actually been asked and has actually answered "nobody" (FR-DEC-01d). */
    public boolean pdsAnsweredNobody() {
        return searches.stream()
                .filter(search -> search.getKind() != SearchKind.ODS_GP_PRACTICE)
                .anyMatch(search -> search.getOutcome() == SearchOutcome.NONE);
    }

    /** Whether every PDS search so far failed rather than answered. */
    public boolean onlyErrors() {
        List<SearchRecord> pds = searches.stream()
                .filter(search -> search.getKind() != SearchKind.ODS_GP_PRACTICE)
                .toList();
        return !pds.isEmpty() && pds.stream()
                .allMatch(search -> search.getOutcome() == SearchOutcome.ERROR);
    }

    public boolean repeatPresentation() {
        return !earlierDecisions.isEmpty();
    }
}
