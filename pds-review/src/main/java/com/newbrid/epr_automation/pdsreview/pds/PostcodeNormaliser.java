package com.newbrid.epr_automation.pdsreview.pds;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns what a patient typed into what PDS can match on, or says plainly that it cannot
 * (FR-PDS-05a, FR-PDS-05b).
 *
 * <p>PDS treats postcode whitespace as significant: {@code LS16AE} does not match a record stored
 * as {@code LS1 6AE}. Passing a free-text answer through as typed therefore produces a search that
 * fails for a formatting reason and looks exactly like a patient who is not on PDS — which is the
 * worst possible failure here, because "not on PDS" is a decision a reviewer can then record.
 * Normalising is what makes that significance harmless.
 *
 * <p>The other half matters as much. A value that will not parse as a UK postcode is
 * <b>not sent at all</b>: padding {@code LS1} into something plausible would search a different
 * address, and inventing an address for a patient nobody has identified yet is the sort of quiet
 * wrongness this application exists to prevent (NFR-SAFE-02). The reviewer is shown what the
 * patient wrote and left to correct it.
 */
public final class PostcodeNormaliser {

    /**
     * The standard UK postcode shape: outward code, then an inward code of one digit and two
     * letters. Applied <em>after</em> the single space is re-inserted, so it validates the value
     * that will actually be sent rather than the one that arrived.
     */
    private static final Pattern UK_POSTCODE =
            Pattern.compile("^[A-Z]{1,2}[0-9][A-Z0-9]? [0-9][A-Z]{2}$");

    /** Everything that is not a letter or a digit; postcodes carry neither punctuation nor spaces. */
    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^A-Z0-9]");

    /** The Girobank postcode, which is real, in use, and does not fit the pattern above. */
    private static final String GIROBANK_COMPACT = "GIR0AA";

    private PostcodeNormaliser() {}

    /**
     * The result of trying to normalise one submitted value. Both forms are carried because the
     * audit trail records both (FR-AUD-02): a nil search result has to be distinguishable from a
     * normalisation that changed the question.
     *
     * @param submitted  exactly what the patient wrote, unaltered
     * @param normalised the value to send, or null when there is none
     * @param reason     why it cannot be sent, or null when it can
     */
    public record Result(String submitted, String normalised, String reason) {

        /** Whether this can be sent to PDS as {@code address-postcode}. */
        public boolean usable() {
            return normalised != null;
        }

        /**
         * The outward code — the part before the space. The only part sent to ODS when searching
         * for practices near a patient's home, because ODS is unauthenticated and the narrowest
         * thing that answers the question is what should cross that boundary (FR-ODS-11).
         */
        public String outwardCode() {
            if (normalised == null) {
                return null;
            }
            return normalised.substring(0, normalised.indexOf(' '));
        }
    }

    /**
     * Normalises a submitted postcode: upper-cased, stripped of whitespace and punctuation, then
     * a single space re-inserted before the final three characters.
     *
     * @param submitted the patient's answer, which may be null, blank, partial or not a postcode
     * @return the value to send, or a {@link Result} explaining why nothing will be sent
     */
    public static Result normalise(String submitted) {
        if (submitted == null || submitted.isBlank()) {
            return new Result(submitted, null, "No postcode was given");
        }

        String compact = NOT_ALPHANUMERIC
                .matcher(submitted.toUpperCase(Locale.UK))
                .replaceAll("");

        if (compact.isEmpty()) {
            return new Result(submitted, null, "No postcode was given");
        }
        if (compact.length() < 5) {
            // An outward code on its own — "LS1", "LS16" — is a district, not an address. There is
            // no way to complete it that is not a guess.
            return new Result(submitted, null,
                    "'" + submitted + "' is not a full postcode — it has no inward code");
        }
        if (compact.length() > 7) {
            return new Result(submitted, null,
                    "'" + submitted + "' is too long to be a UK postcode");
        }

        if (GIROBANK_COMPACT.equals(compact)) {
            // Spaced like any other postcode, but it does not fit the standard pattern and must
            // skip the check below rather than be rejected by it.
            return new Result(submitted, "GIR 0AA", null);
        }

        String spaced = compact.substring(0, compact.length() - 3)
                + " " + compact.substring(compact.length() - 3);

        if (!UK_POSTCODE.matcher(spaced).matches()) {
            return new Result(submitted, null,
                    "'" + submitted + "' is not in a recognised UK postcode format");
        }
        return new Result(submitted, spaced, null);
    }
}
