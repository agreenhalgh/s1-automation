package com.newbrid.epr_automation.gateway.route;

import com.newbrid.epr_automation.gateway.GatewayProperties;
import com.newbrid.epr_automation.gateway.api.RunSubmission;
import com.newbrid.epr_automation.gateway.controlplane.ControlPlaneWire.DispatchRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The transform step and the one validation rule that needs arithmetic rather than an annotation. */
class SubmissionTransformerTest {

    private final GatewayProperties properties = new GatewayProperties();
    private final SubmissionTransformer transformer = new SubmissionTransformer(properties);

    // ---- transform ------------------------------------------------------------------

    @Test
    void mapsTheExternalNamesOntoTheControlPlaneRequest() {
        RunSubmission submission = new RunSubmission("caller-1", "get-postcode",
                new RunSubmission.Subject("9434765919", "L-42"),
                Map.of("searchTerm", "Smith"), "ward-a", null, null, null);

        DispatchRequest dispatched = transformer.toDispatchRequest(submission);

        assertThat(dispatched.botId()).isEqualTo("get-postcode");
        assertThat(dispatched.capabilityTag()).isEqualTo("ward-a");
        assertThat(dispatched.input())
                .containsEntry("searchTerm", "Smith")
                .containsEntry("subject.nhsNumber", "9434765919")
                .containsEntry("subject.localId", "L-42");
    }

    @Test
    void omitsAnAbsentSubjectRatherThanSendingEmptyKeys() {
        RunSubmission submission = new RunSubmission(null, "no-patient-bot", null,
                Map.of("a", "1"), null, null, null, null);

        DispatchRequest dispatched = transformer.toDispatchRequest(submission);

        assertThat(dispatched.input()).containsOnlyKeys("a");
        assertThat(dispatched.capabilityTag())
                .as("a blank pool must become null, not an empty capability tag that matches nothing")
                .isNull();
    }

    @Test
    void blankPoolBecomesNull() {
        RunSubmission submission = new RunSubmission(null, "bot", null, null, "   ", null, null, null);

        assertThat(transformer.toDispatchRequest(submission).capabilityTag()).isNull();
    }

    // ---- the wait budget ------------------------------------------------------------

    @Test
    void usesTheConfiguredDefaultWhenTheCallerDoesNotAsk() {
        properties.getRun().setDefaultWaitSeconds(90);

        RunSubmission submission = new RunSubmission(null, "bot", null, null, null, null, null, null);

        assertThat(transformer.waitMillisFor(submission)).isEqualTo(90_000L);
    }

    @Test
    void clampsAnOverAmbitiousWaitToThePolicyCeiling() {
        properties.getRun().setMaxWaitSeconds(300);

        RunSubmission submission = new RunSubmission(null, "bot", null, null, null, 1200, null, null);

        assertThat(transformer.waitMillisFor(submission))
                .as("clamped rather than rejected — the request is reasonable, policy overrides it")
                .isEqualTo(300_000L);
    }

    // ---- NHS number check digit -----------------------------------------------------

    /**
     * 9434765919 is the widely published NHS test number; 9876543210 happens to check out too.
     * Both are computed rather than looked up, so they pin the arithmetic and not a constant.
     */
    @ParameterizedTest
    @ValueSource(strings = {"9434765919", "9876543210"})
    void acceptsANumberWhoseCheckDigitComputes(String nhsNumber) {
        assertThat(SubmissionTransformer.hasValidNhsCheckDigit(nhsNumber)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "9434765918",   // right shape, wrong check digit
            "9434765910",
            "943476591",    // nine digits
            "94347659199",  // eleven
            "943476591X",   // not all digits
            "94347 65919"   // spaces are not stripped for us
    })
    void rejectsANumberThatIsNotOne(String candidate) {
        assertThat(SubmissionTransformer.hasValidNhsCheckDigit(candidate)).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(SubmissionTransformer.hasValidNhsCheckDigit(null)).isFalse();
    }

    /**
     * When the modulus-11 remainder is 1 the check digit would have to be 10, which no single
     * digit can be — so such numbers are never issued and none of the ten possible last digits
     * can make one valid. 000000006x has that remainder.
     */
    @Test
    void rejectsEveryNumberWhoseCheckDigitWouldHaveToBeTen() {
        for (int last = 0; last <= 9; last++) {
            String candidate = "000000006" + last;
            assertThat(SubmissionTransformer.hasValidNhsCheckDigit(candidate))
                    .as("%s would need a check digit of 10", candidate)
                    .isFalse();
        }
    }
}
