package com.newbrid.epr_automation.gateway.audit;

import com.newbrid.epr_automation.gateway.GatewayProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The properties an audit reference has to have to be worth writing down: stable enough to
 * follow, and not a copy of the identifier it stands for (NFR-SEC-05).
 */
class SubjectPseudonymiserTest {

    private static final String NHS_NUMBER = "9434765919";

    private static SubjectPseudonymiser withKey(String key) {
        GatewayProperties properties = new GatewayProperties();
        properties.getAudit().setPseudonymKey(key);
        return new SubjectPseudonymiser(properties);
    }

    private final SubjectPseudonymiser pseudonymiser = withKey("a-test-key");

    @Test
    void theSamePatientAlwaysGetsTheSameReference() {
        assertThat(pseudonymiser.reference("nhs", NHS_NUMBER))
                .as("without this an auditor cannot follow one patient through the trail")
                .isEqualTo(pseudonymiser.reference("nhs", NHS_NUMBER));
    }

    @Test
    void theReferenceDoesNotContainTheIdentifier() {
        assertThat(pseudonymiser.reference("nhs", NHS_NUMBER)).doesNotContain(NHS_NUMBER);
    }

    @Test
    void differentPatientsGetDifferentReferences() {
        assertThat(pseudonymiser.reference("nhs", NHS_NUMBER))
                .isNotEqualTo(pseudonymiser.reference("nhs", "9876543210"));
    }

    /**
     * The key is what makes the reference a pseudonym rather than an encoding: there are only ten
     * billion possible NHS numbers, so an unkeyed digest of one is reversible by exhaustive
     * search. Two deployments with different keys must not produce the same reference.
     */
    @Test
    void aDifferentKeyProducesADifferentReference() {
        assertThat(withKey("key-one").reference("nhs", NHS_NUMBER))
                .isNotEqualTo(withKey("key-two").reference("nhs", NHS_NUMBER));
    }

    @Test
    void theSameDigitsUnderDifferentIdentifierTypesDoNotCollide() {
        assertThat(pseudonymiser.reference("nhs", "1234567890"))
                .isNotEqualTo(pseudonymiser.reference("local", "1234567890"));
    }

    @Test
    void theReferenceSaysWhatKindOfIdentifierItStandsFor() {
        assertThat(pseudonymiser.reference("nhs", NHS_NUMBER)).startsWith("nhs-");
        assertThat(pseudonymiser.reference("local", "L-1")).startsWith("local-");
    }

    @Test
    void nothingToReferenceYieldsNothing() {
        assertThat(pseudonymiser.reference("nhs", null)).isNull();
        assertThat(pseudonymiser.reference("nhs", "   ")).isNull();
    }

    @Test
    void whitespaceAroundAnIdentifierDoesNotChangeItsReference() {
        assertThat(pseudonymiser.reference("nhs", " " + NHS_NUMBER + " "))
                .isEqualTo(pseudonymiser.reference("nhs", NHS_NUMBER));
    }
}
