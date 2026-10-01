package com.newbrid.epr_automation.pdsreview.decision;

import com.newbrid.epr_automation.pdsreview.domain.Decision;
import com.newbrid.epr_automation.pdsreview.domain.DecisionClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules that decide whether a patient gets registered (FR-DEC-01, FR-DEC-01a … 01d).
 *
 * <p>These are cheap tests guarding an expensive mistake. Getting the class of a decision wrong
 * means either registering a patient nobody meant to register, or closing an item on a patient
 * who is still waiting to be — and both look like ordinary rows in a queue afterwards.
 */
class DecisionRulesTest {

    @Test
    void threeDecisionsRegisterAPatientAndTwoDoNot() {
        assertThat(Decision.values()).hasSize(5);

        assertThat(Decision.MATCHED.registers()).isTrue();
        assertThat(Decision.ACCEPTED_NOT_ON_PDS.registers()).isTrue();
        assertThat(Decision.ACCEPTED_PATIENT_UNAVAILABLE.registers()).isTrue();

        assertThat(Decision.CLOSED_REGISTERED_MANUALLY.registers()).isFalse();
        assertThat(Decision.CLOSED_NO_LONGER_REQUIRED.registers()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Decision.class)
    void theClassIsReadableFromTheNameAlone(Decision decision) {
        // FR-DEC-01a: the gateway route branches on the prefix, so a reason code it has never
        // heard of still says whether it was meant to dispatch anything. If a future decision is
        // added with a name that breaks this, it breaks here first.
        boolean prefixSaysRegistering =
                decision.name().startsWith("MATCHED") || decision.name().startsWith("ACCEPTED_");
        boolean prefixSaysClosing = decision.name().startsWith("CLOSED_");

        assertThat(prefixSaysRegistering ^ prefixSaysClosing)
                .as("%s must carry exactly one recognisable prefix", decision)
                .isTrue();
        assertThat(prefixSaysRegistering)
                .isEqualTo(decision.decisionClass() == DecisionClass.REGISTERING);
    }

    @Test
    void everyClosureNeedsAReason() {
        assertThat(Decision.CLOSED_REGISTERED_MANUALLY.requiresNote()).isTrue();
        assertThat(Decision.CLOSED_NO_LONGER_REQUIRED.requiresNote()).isTrue();
        assertThat(Decision.MATCHED.requiresNote()).isFalse();
    }

    @Test
    void onlyTheManualRegistrationClosureAsksWhereTheRecordIs() {
        assertThat(Decision.CLOSED_REGISTERED_MANUALLY.requiresExistingRecordPrompt()).isTrue();
        assertThat(Decision.CLOSED_NO_LONGER_REQUIRED.requiresExistingRecordPrompt()).isFalse();
    }

    @Test
    void acceptUnmatchedDecisionsHaveToSayWhatPdsWasAsked() {
        // FR-DEC-01d: with the search order no longer enforced, "they must have searched" cannot
        // be inferred from reaching the decision screen.
        assertThat(Decision.ACCEPTED_NOT_ON_PDS.requiresSearchEvidence()).isTrue();
        assertThat(Decision.ACCEPTED_PATIENT_UNAVAILABLE.requiresSearchEvidence()).isTrue();
        assertThat(Decision.MATCHED.requiresSearchEvidence()).isFalse();
        assertThat(Decision.CLOSED_NO_LONGER_REQUIRED.requiresSearchEvidence()).isFalse();
    }

    // ---- what a request has to carry before it is recorded ---------------------------

    @Test
    void aClosureWithoutAReasonIsRefused() {
        DecisionRequest noReason = new DecisionRequest(Decision.CLOSED_NO_LONGER_REQUIRED,
                "  ", null, null, true);

        assertThat(noReason.valid()).isFalse();
        assertThat(noReason.problems()).singleElement().asString().contains("needs a reason");
    }

    @Test
    void aClosureWithAReasonIsAccepted() {
        DecisionRequest closure = new DecisionRequest(Decision.CLOSED_NO_LONGER_REQUIRED,
                "Patient left the department before being seen", null, null, true);

        assertThat(closure.problems()).isEmpty();
    }

    @Test
    void aManualRegistrationClosureDoesNotForceAnInventedRecordNumber() {
        // Prompted for, recorded when given, not compulsory — a reviewer who knows a
        // registration happened but not its number should not have to make one up (FR-DEC-01b).
        DecisionRequest withoutTheNumber = new DecisionRequest(Decision.CLOSED_REGISTERED_MANUALLY,
                "Registered on the EPR by the ward clerk", null, null, true);

        assertThat(withoutTheNumber.valid()).isTrue();
    }

    @Test
    void aMatchWithoutACandidateIsRefused() {
        DecisionRequest noCandidate = new DecisionRequest(Decision.MATCHED, null, null, null, true);

        assertThat(noCandidate.problems()).singleElement().asString().contains("Select the PDS candidate");
    }

    @Test
    void aCandidateSelectedAlongsideANonMatchingDecisionIsRefused() {
        // A form in a state nobody intended. Refusing beats guessing which the reviewer meant.
        DecisionRequest confused = new DecisionRequest(Decision.ACCEPTED_NOT_ON_PDS,
                null, null, "9434765919", true);

        assertThat(confused.problems()).singleElement().asString()
                .contains("does not register one");
    }

    @Test
    void nothingIsRecordedWithoutConfirmation() {
        DecisionRequest unconfirmed = new DecisionRequest(Decision.ACCEPTED_PATIENT_UNAVAILABLE,
                null, null, null, false);

        assertThat(unconfirmed.problems()).singleElement().asString().contains("Confirm");
    }
}
