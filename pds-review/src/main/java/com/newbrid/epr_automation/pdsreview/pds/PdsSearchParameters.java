package com.newbrid.epr_automation.pdsreview.pds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One PDS {@code GET /Patient} search, as it will be sent.
 *
 * <p>There are two factories and no public constructor, and that is the point. PDS ignores
 * {@code death-date} and {@code general-practitioner} in a fuzzy search, so
 * {@link #fuzzy} cannot be handed them — offering a reviewer a parameter that will be silently
 * discarded implies a narrowing that does not happen (FR-FUZ-04). Making it impossible to
 * construct beats validating it afterwards.
 *
 * <p>The combination rules are here rather than in a controller because they are PDS's rules, not
 * the form's. A combination PDS will refuse is a form validation error shown before anything is
 * sent (FR-FUZ-03) — the reviewer should see "add a date of birth", not a 400 from a national
 * service.
 *
 * @param family              surname. Required for a non-fuzzy search
 * @param given               forename
 * @param gender              already mapped onto PDS's value set by {@code DataItemMapping}
 * @param birthdate           ISO date, sent with an {@code eq}/{@code ge}/{@code le} prefix
 * @param postcode            already normalised (FR-PDS-05a); never a raw patient answer
 * @param generalPractitioner ODS code of the registered practice. Non-fuzzy only
 * @param deathDate           non-fuzzy only
 * @param history             search previous names and addresses too — always true for the
 *                            automatic first search (FR-PDS-02)
 * @param fuzzy               homophone and transposed-name tolerant
 * @param maxResults          PDS caps this at 50 and answers TOO_MANY_MATCHES beyond it
 */
public record PdsSearchParameters(
        String family,
        String given,
        String gender,
        String birthdate,
        String postcode,
        String generalPractitioner,
        String deathDate,
        boolean history,
        boolean fuzzy,
        int maxResults) {

    /** How a date is qualified. PDS wants the prefix on the value (FR-PDS-05). */
    public enum DatePrefix { eq, ge, le }

    /**
     * A deterministic search. {@code family}, {@code gender} and {@code birthdate} are what PDS
     * requires; the caller is expected to have checked they exist, because
     * {@link #problems()} is what turns their absence into something a reviewer can read.
     */
    public static PdsSearchParameters nonFuzzy(String family, String given, String gender,
                                               String birthdate, String postcode,
                                               String generalPractitioner, String deathDate,
                                               boolean history, int maxResults) {
        return new PdsSearchParameters(trim(family), trim(given), trim(gender), trim(birthdate),
                trim(postcode), trim(generalPractitioner), trim(deathDate),
                history, false, maxResults);
    }

    /**
     * A fuzzy search. No {@code deathDate}, no {@code generalPractitioner} — PDS would discard
     * both (FR-FUZ-04).
     */
    public static PdsSearchParameters fuzzy(String family, String given, String gender,
                                            String birthdate, String postcode,
                                            boolean history, int maxResults) {
        return new PdsSearchParameters(trim(family), trim(given), trim(gender), trim(birthdate),
                trim(postcode), null, null, history, true, maxResults);
    }

    /**
     * Why PDS would refuse this search, in words a reviewer can act on. Empty means it can be
     * sent.
     *
     * <p>Non-fuzzy needs family name, gender and date of birth. Fuzzy needs one of three
     * combinations, and the message names them rather than saying "invalid combination", because
     * a reviewer staring at a form needs to know which field to add.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!fuzzy) {
            if (blank(family)) {
                problems.add("A family name is needed for a non-fuzzy search");
            }
            if (blank(gender)) {
                problems.add("A gender is needed for a non-fuzzy search");
            }
            if (blank(birthdate)) {
                problems.add("A date of birth is needed for a non-fuzzy search");
            }
            return problems;
        }
        if (satisfiesAnyFuzzyCombination()) {
            return problems;
        }
        problems.add("A fuzzy search needs one of: given name with family name and date of birth; "
                + "or family name, date of birth, gender and postcode; "
                + "or given name, date of birth, gender and postcode");
        return problems;
    }

    /** The three combinations PDS accepts for a fuzzy search. */
    private boolean satisfiesAnyFuzzyCombination() {
        boolean namesAndBirth = present(given) && present(family) && present(birthdate);
        boolean familyGenderPostcode =
                present(family) && present(birthdate) && present(gender) && present(postcode);
        boolean givenGenderPostcode =
                present(given) && present(birthdate) && present(gender) && present(postcode);
        return namesAndBirth || familyGenderPostcode || givenGenderPostcode;
    }

    public boolean valid() {
        return problems().isEmpty();
    }

    /**
     * The query as PDS receives it. A {@link LinkedHashMap} so the order is stable, which is what
     * makes {@link #canonical()} comparable between two searches in the audit trail.
     */
    public Map<String, String> query() {
        Map<String, String> query = new LinkedHashMap<>();
        put(query, "family", family);
        put(query, "given", given);
        put(query, "gender", gender);
        if (present(birthdate)) {
            query.put("birthdate", prefixed(birthdate));
        }
        put(query, "address-postcode", postcode);
        put(query, "general-practitioner", generalPractitioner);
        if (present(deathDate)) {
            query.put("death-date", prefixed(deathDate));
        }
        if (history) {
            query.put("_history", "true");
        }
        if (fuzzy) {
            query.put("_fuzzy-match", "true");
        }
        query.put("_max-results", String.valueOf(maxResults));
        return query;
    }

    /**
     * The search as one line, for the audit trail (FR-AUD-02). Records exactly what was sent —
     * including {@code _history} and {@code _fuzzy-match}, which change what the results mean.
     */
    public String canonical() {
        StringBuilder text = new StringBuilder();
        query().forEach((name, value) -> {
            if (!text.isEmpty()) {
                text.append('&');
            }
            text.append(name).append('=').append(value);
        });
        return text.toString();
    }

    /** A date already carrying a prefix is left alone; a bare one is treated as exact. */
    private static String prefixed(String date) {
        for (DatePrefix prefix : DatePrefix.values()) {
            if (date.startsWith(prefix.name())) {
                return date;
            }
        }
        return DatePrefix.eq + date;
    }

    private static void put(Map<String, String> query, String name, String value) {
        if (present(value)) {
            query.put(name, value);
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean blank(String value) {
        return !present(value);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
