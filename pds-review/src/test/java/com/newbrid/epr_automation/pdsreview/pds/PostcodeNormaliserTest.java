package com.newbrid.epr_automation.pdsreview.pds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-PDS-05a and FR-PDS-05b, which between them decide whether a search asks the right question.
 *
 * <p>Both halves matter equally. Normalising is what stops {@code ls16ae} failing to match a
 * record stored as {@code LS1 6AE} — a formatting failure that looks exactly like a patient who
 * is not on PDS, which is a conclusion a reviewer can then act on. Refusing to normalise the
 * unnormalisable is what stops {@code LS1} being padded into an address nobody lives at.
 */
class PostcodeNormaliserTest {

    @ParameterizedTest
    @CsvSource({
            "ls16ae,       LS1 6AE",
            "LS1 6AE,      LS1 6AE",
            "  ls1  6ae  , LS1 6AE",
            "ls1-6ae,      LS1 6AE",
            "SW1A1AA,      SW1A 1AA",
            "sw1a 1aa,     SW1A 1AA",
            "m11ae,        M1 1AE",
            "b338th,       B33 8TH",
            "cr26xh,       CR2 6XH",
            "dn551pt,      DN55 1PT",
            "ec1a1bb,      EC1A 1BB",
            "w1a0ax,       W1A 0AX"
    })
    void normalisesWhateverThePatientTyped(String submitted, String expected) {
        PostcodeNormaliser.Result result = PostcodeNormaliser.normalise(submitted);

        assertThat(result.usable()).as("'%s' should be usable", submitted).isTrue();
        assertThat(result.normalised()).isEqualTo(expected);
        assertThat(result.submitted())
                .as("what the patient wrote is kept, for the audit trail")
                .isEqualTo(submitted);
    }

    @Test
    void spacingIsTheWholePoint() {
        // The two forms a patient will type for the same address must reach PDS identically —
        // PDS treats the whitespace as significant, so this is the difference between a match
        // and an apparent absence.
        assertThat(PostcodeNormaliser.normalise("LS16AE").normalised())
                .isEqualTo(PostcodeNormaliser.normalise("LS1 6AE").normalised());
    }

    @Test
    void keepsTheGirobankPostcode() {
        // GIR 0AA is a real, in-use postcode that does not fit the standard pattern. Rejecting it
        // would mean refusing to search for somebody who genuinely lives at it.
        PostcodeNormaliser.Result result = PostcodeNormaliser.normalise("gir0aa");

        assertThat(result.usable()).isTrue();
        assertThat(result.normalised()).isEqualTo("GIR 0AA");
    }

    @ParameterizedTest
    @ValueSource(strings = {"LS1", "LS", "L", "LS16", "12345", "not a postcode",
            "LS1 6AE extra", "SW1A1AA1AA"})
    void refusesToInventTheRest(String submitted) {
        PostcodeNormaliser.Result result = PostcodeNormaliser.normalise(submitted);

        assertThat(result.usable())
                .as("'%s' must not be padded into something plausible", submitted)
                .isFalse();
        assertThat(result.normalised()).isNull();
        assertThat(result.reason())
                .as("the reviewer is told why, and can correct it")
                .isNotBlank();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "-"})
    void treatsNothingAsNothing(String submitted) {
        PostcodeNormaliser.Result result = PostcodeNormaliser.normalise(submitted);

        assertThat(result.usable()).isFalse();
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void outwardCodeIsAllThatGoesToAnUnauthenticatedDirectory() {
        // FR-ODS-11: searching for practices near a patient's home sends the district, not the
        // address.
        PostcodeNormaliser.Result result = PostcodeNormaliser.normalise("ls1 6ae");

        assertThat(result.outwardCode()).isEqualTo("LS1");
    }

    @Test
    void anUnusableValueHasNoOutwardCodeEither() {
        assertThat(PostcodeNormaliser.normalise("nonsense").outwardCode()).isNull();
    }
}
