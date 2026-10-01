package com.newbrid.epr_automation.pdsreview.pds;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDS's own rules about which parameter combinations it will accept, enforced before anything is
 * sent (FR-PDS-04, FR-FUZ-03).
 *
 * <p>The reason these are checked here rather than left to PDS is what a reviewer sees. A
 * combination PDS refuses comes back as a 400 from a national service; the same thing caught here
 * is a form telling them to add a date of birth.
 */
class PdsSearchParametersTest {

    // ---- non-fuzzy: family, gender and birthdate are all required -------------------

    @Test
    void nonFuzzyNeedsFamilyGenderAndBirthdate() {
        PdsSearchParameters complete = PdsSearchParameters.nonFuzzy("SMITH", "AVA", "female",
                "1947-10-13", "LS1 6AE", null, null, true, 50);

        assertThat(complete.valid()).isTrue();
        assertThat(complete.problems()).isEmpty();
    }

    @Test
    void namesEachMissingRequiredFieldSeparately() {
        PdsSearchParameters incomplete = PdsSearchParameters.nonFuzzy(null, "AVA", null,
                null, "LS1 6AE", null, null, true, 50);

        // Three separate problems, not "invalid search": a reviewer fixing them one round trip at
        // a time is a reviewer who gives up and decides without searching.
        assertThat(incomplete.problems()).hasSize(3);
        assertThat(incomplete.problems())
                .anySatisfy(problem -> assertThat(problem).contains("family name"))
                .anySatisfy(problem -> assertThat(problem).contains("gender"))
                .anySatisfy(problem -> assertThat(problem).contains("date of birth"));
    }

    // ---- fuzzy: one of three combinations --------------------------------------------

    @Test
    void fuzzyAcceptsGivenFamilyAndBirthdate() {
        assertThat(PdsSearchParameters.fuzzy("SMITH", "AVA", null, "1947-10-13", null, true, 50)
                .valid()).isTrue();
    }

    @Test
    void fuzzyAcceptsFamilyBirthdateGenderAndPostcode() {
        assertThat(PdsSearchParameters.fuzzy("SMITH", null, "female", "1947-10-13", "LS1 6AE",
                true, 50).valid()).isTrue();
    }

    @Test
    void fuzzyAcceptsGivenBirthdateGenderAndPostcode() {
        assertThat(PdsSearchParameters.fuzzy(null, "AVA", "female", "1947-10-13", "LS1 6AE",
                true, 50).valid()).isTrue();
    }

    @Test
    void fuzzyRefusesACombinationPdsWouldReject() {
        PdsSearchParameters justANameAndGender =
                PdsSearchParameters.fuzzy("SMITH", null, "female", null, null, true, 50);

        assertThat(justANameAndGender.valid()).isFalse();
        assertThat(justANameAndGender.problems())
                .singleElement()
                .asString()
                .as("the message names the combinations, so the reviewer knows what to add")
                .contains("given name with family name and date of birth");
    }

    @Test
    void fuzzyCannotBeGivenTheParametersPdsIgnores() {
        // FR-FUZ-04 is enforced by the type: the fuzzy factory has no death-date and no
        // general-practitioner argument, so there is no way to offer a reviewer a parameter that
        // would be silently discarded.
        PdsSearchParameters fuzzy = PdsSearchParameters.fuzzy("SMITH", "AVA", null,
                "1947-10-13", null, true, 50);

        assertThat(fuzzy.deathDate()).isNull();
        assertThat(fuzzy.generalPractitioner()).isNull();
        assertThat(fuzzy.canonical())
                .doesNotContain("death-date")
                .doesNotContain("general-practitioner");
    }

    // ---- what actually goes on the wire ----------------------------------------------

    @Test
    void sendsHistoryAndNeverExactMatch() {
        PdsSearchParameters search = PdsSearchParameters.nonFuzzy("SMITH", "AVA", "female",
                "1947-10-13", "LS1 6AE", null, null, true, 50);

        assertThat(search.query())
                .containsEntry("_history", "true")
                .containsEntry("_max-results", "50")
                // FR-PDS-08: restricting to score 1.0 would hide the near-misses a human is here
                // to adjudicate.
                .doesNotContainKey("_exact-match");
        assertThat(search.query()).doesNotContainKey("_fuzzy-match");
    }

    @Test
    void qualifiesTheDateOfBirthAsExactUnlessTheReviewerSaidOtherwise() {
        assertThat(PdsSearchParameters.nonFuzzy("SMITH", null, "female", "1947-10-13", null,
                        null, null, true, 50).query())
                .containsEntry("birthdate", "eq1947-10-13");

        // A reviewer who typed a range keeps it — this is how an uncertain year of birth is
        // searched (FR-FUZ-06).
        assertThat(PdsSearchParameters.fuzzy("SMITH", "AVA", null, "ge1947-01-01", null, true, 50)
                .query())
                .containsEntry("birthdate", "ge1947-01-01");
    }

    @Test
    void canonicalFormIsStableSoTwoSearchesCanBeCompared() {
        String first = PdsSearchParameters.nonFuzzy("SMITH", "AVA", "female", "1947-10-13",
                "LS1 6AE", null, null, true, 50).canonical();
        String second = PdsSearchParameters.nonFuzzy("SMITH", "AVA", "female", "1947-10-13",
                "LS1 6AE", null, null, true, 50).canonical();

        assertThat(first).isEqualTo(second);
        assertThat(first).contains("family=SMITH", "gender=female", "address-postcode=LS1 6AE");
    }

    @Test
    void gpPractitionerNarrowsButDoesNotReplaceTheRequiredFields() {
        // FR-ODS-07: a practice-scoped search still needs family, gender and birthdate.
        PdsSearchParameters withoutRequired = PdsSearchParameters.nonFuzzy(null, null, null,
                null, null, "A81001", null, true, 50);

        assertThat(withoutRequired.valid()).isFalse();

        PdsSearchParameters withRequired = PdsSearchParameters.nonFuzzy("SMITH", null, "female",
                "1947-10-13", null, "A81001", null, true, 50);

        assertThat(withRequired.valid()).isTrue();
        assertThat(withRequired.query()).containsEntry("general-practitioner", "A81001");
    }
}
