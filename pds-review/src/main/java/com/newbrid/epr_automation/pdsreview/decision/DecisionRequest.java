package com.newbrid.epr_automation.pdsreview.decision;

import com.newbrid.epr_automation.pdsreview.domain.Decision;

import java.util.ArrayList;
import java.util.List;

/**
 * What a reviewer is asking to record, before anything is written down.
 *
 * <p>{@link #problems()} is where FR-DEC-01b, FR-DEC-01c and FR-DEC-03 are enforced, and it is
 * deliberately here rather than in a controller: these are rules about what a decision <em>is</em>,
 * not about how a form was submitted. A closure with no note is not a valid decision however it
 * arrives.
 *
 * @param decision         which of the five
 * @param note             free text. Required of every closure — "no longer requires
 *                         registration" with no reason is a closure nobody can review afterwards
 * @param existingRecordId where the patient was already registered, for a manual-registration
 *                         closure. Prompted for, recorded when given, not compulsory: a reviewer
 *                         who knows a registration happened but not its number should not be
 *                         forced to invent one
 * @param nhsNumber        the selected candidate, for MATCHED
 * @param confirmed        the reviewer confirmed a screen that restated the consequence —
 *                         registration, or closure with none (FR-DEC-03)
 */
public record DecisionRequest(Decision decision, String note, String existingRecordId,
                              String nhsNumber, boolean confirmed) {

    /** Everything wrong with this request, in words a reviewer can act on. Empty means valid. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (decision == null) {
            problems.add("Choose what you have decided");
            return problems;
        }
        if (!confirmed) {
            problems.add("Confirm what will happen before the decision is recorded");
        }
        if (decision.requiresNote() && blank(note)) {
            problems.add("Closing this item without registering anybody needs a reason");
        }
        if (decision == Decision.MATCHED && blank(nhsNumber)) {
            problems.add("Select the PDS candidate this patient is");
        }
        if (decision != Decision.MATCHED && !blank(nhsNumber)) {
            // A candidate selected alongside a decision that does not register one is a form
            // that has got into a state nobody intended; refusing beats guessing which the
            // reviewer meant.
            problems.add("A PDS candidate is selected, but this decision does not register one");
        }
        return problems;
    }

    public boolean valid() {
        return problems().isEmpty();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
