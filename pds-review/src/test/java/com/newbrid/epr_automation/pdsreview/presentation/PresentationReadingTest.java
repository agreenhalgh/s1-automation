package com.newbrid.epr_automation.pdsreview.presentation;

import com.newbrid.epr_automation.pdsreview.SamplePresentation;
import com.newbrid.epr_automation.pdsreview.domain.UnmatchedReason;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a real presentation, and turning a patient's answers into search parameters.
 *
 * <p>Run against a captured payload rather than a hand-made one on purpose. The whole difficulty
 * of §4.2 is that the sender populates the stable {@code key} for exactly one field and leaves
 * free-text question wording as the only handle on the rest — a fixture written from the schema
 * would not have that property, and would pass while the real thing failed.
 */
class PresentationReadingTest {

    private static final JsonMapper JSON = new JsonMapper();

    private final DataItemMapping mapping =
            new DataItemMapping(new DefaultResourceLoader(), "classpath:data-item-mapping.yaml");

    @Test
    void readsWhyThePatientNeedsReviewing() throws IOException {
        PresentationView presentation = captured();

        assertThat(presentation.unmatched())
                .as("nhsUser is null in every captured payload, so every one needs a human")
                .isTrue();
        assertThat(presentation.unmatchedReason())
                .as("user.nhs is absent entirely, which is weaker than either PDS answer")
                .isEqualTo(UnmatchedReason.NOT_STATED);
    }

    @Test
    void readsTheFieldsTheWorklistOrdersAndShows() throws IOException {
        PresentationView presentation = captured();

        assertThat(presentation.sessionId()).contains("6a3c07f79aa78a4853b67c15");
        assertThat(presentation.triageScore()).contains("P5");
        assertThat(presentation.triageEndTimestamp()).isPresent();
        assertThat(presentation.hospitalName()).isPresent();
        assertThat(presentation.departmentCode()).contains("ED");
        assertThat(presentation.presentingComplaint()).isPresent();
        assertThat(presentation.hasPdf()).isTrue();
    }

    @Test
    void derivesEverythingPdsNeedsForANonFuzzySearch() throws IOException {
        DataItemMapping.Derived derived = mapping.derive(captured().answers());

        assertThat(derived.canSearchNonFuzzy())
                .as("family, gender and birthdate must all come out of the patient's answers")
                .isTrue();
        assertThat(derived.givenName()).isEqualTo("Ava");
        assertThat(derived.familyName()).isNotBlank();
        assertThat(derived.birthdate()).isEqualTo("1947-10-13");
        assertThat(derived.gender())
                .as("mapped onto the value set PDS accepts, not passed through as typed")
                .isIn("male", "female", "other", "unknown");
    }

    @Test
    void prefersTheStableKeyOverTheQuestionText() throws IOException {
        // dob is the one field the sender keys. If the mapping ever stops preferring it, this is
        // where that shows up.
        DataItemMapping.Derived derived = mapping.derive(captured().answers());

        assertThat(derived.submitted()).containsEntry("birthdate", "1947-10-13");
    }

    @Test
    void namesWhatIsMissingRatherThanSearchingWithoutIt() {
        // The failure R1 predicts: somebody renames "Last Name" upstream and a required parameter
        // silently disappears. It must surface as a named gap, not as a two-field search.
        List<PatientAnswer> renamedUpstream = List.of(
                new PatientAnswer("First Name", "Ava", "", "text", "f1"),
                new PatientAnswer("Patient Family Name", "Smith", "", "text", "f2"),
                new PatientAnswer("Date of Birth", "1947-10-13", "dob", "date", "f3"));

        DataItemMapping.Derived derived = mapping.derive(renamedUpstream);

        assertThat(derived.canSearchNonFuzzy()).isFalse();
        assertThat(derived.missingRequired()).contains("family", "gender");
    }

    @Test
    void toleratesPunctuationAndSpacingInQuestionWording() {
        List<PatientAnswer> punctuated = List.of(
                new PatientAnswer("  Last   Name? ", "Smith", "", "text", "f1"),
                new PatientAnswer("Sex:", "Female", "", "radio", "f2"),
                new PatientAnswer("Date of Birth", "1947-10-13", "", "date", "f3"));

        DataItemMapping.Derived derived = mapping.derive(punctuated);

        assertThat(derived.canSearchNonFuzzy()).isTrue();
        assertThat(derived.familyName()).isEqualTo("Smith");
        assertThat(derived.gender()).isEqualTo("female");
    }

    @Test
    void refusesADateItCannotBeSureOf() {
        List<PatientAnswer> ambiguous = List.of(
                new PatientAnswer("Last Name", "Smith", "", "text", "f1"),
                new PatientAnswer("Sex", "Female", "", "radio", "f2"),
                new PatientAnswer("Date of Birth", "13 October 1947", "dob", "text", "f3"));

        DataItemMapping.Derived derived = mapping.derive(ambiguous);

        assertThat(derived.missingRequired()).contains("birthdate");
        assertThat(derived.notes()).containsKey("birthdate");
    }

    @Test
    void acceptsTheUkDateFormatAPatientWillActuallyType() {
        List<PatientAnswer> ukFormat = List.of(
                new PatientAnswer("Last Name", "Smith", "", "text", "f1"),
                new PatientAnswer("Sex", "Male", "", "radio", "f2"),
                new PatientAnswer("Date of Birth", "13/10/1947", "dob", "text", "f3"));

        assertThat(mapping.derive(ukFormat).birthdate()).isEqualTo("1947-10-13");
    }

    @Test
    void doesNotGuessAGenderPdsWouldReject() {
        List<PatientAnswer> unmapped = List.of(
                new PatientAnswer("Last Name", "Smith", "", "text", "f1"),
                new PatientAnswer("Sex", "Woman", "", "radio", "f2"),
                new PatientAnswer("Date of Birth", "1947-10-13", "dob", "date", "f3"));

        DataItemMapping.Derived derived = mapping.derive(unmapped);

        assertThat(derived.gender()).isNull();
        assertThat(derived.missingRequired()).contains("gender");
        assertThat(derived.notes().get("gender")).contains("Woman");
    }

    @Test
    void aMatchedPresentationIsNotUnmatched() {
        PresentationView matched = new PresentationView(JSON.readTree("""
                {"externalValue":{
                   "nhsUser":{"id":"9434765919"},
                   "user":{"_id":"abc","nhs":{"status":"successful"}}}}
                """));

        assertThat(matched.unmatched()).isFalse();
    }

    @Test
    void distinguishesPdsSayingNoFromPdsNotAnswering() {
        assertThat(withStatus("unsuccessful").unmatchedReason())
                .isEqualTo(UnmatchedReason.PDS_LOOKUP_UNSUCCESSFUL);
        assertThat(withStatus("unavailable").unmatchedReason())
                .isEqualTo(UnmatchedReason.PDS_UNAVAILABLE);
    }

    private static PresentationView withStatus(String status) {
        return new PresentationView(JSON.readTree("""
                {"externalValue":{"nhsUser":null,
                   "user":{"_id":"abc","nhs":{"status":"%s"}}}}
                """.formatted(status)));
    }

    private static PresentationView captured() throws IOException {
        return new PresentationView(SamplePresentation.unmatched());
    }
}
