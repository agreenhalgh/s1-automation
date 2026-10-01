package com.newbrid.epr_automation.pdsreview.pds;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * One patient PDS offered as a possible match, with the score it gave them.
 *
 * <p>The score is carried and displayed, never acted on. A score of 1.0 is an exact match on the
 * fields searched, which is not the same as being the right person — a mother and daughter at the
 * same address with the same forename will both score well. Nothing here or downstream selects a
 * candidate automatically, at any score (NFR-SAFE-01).
 *
 * <p>{@code raw} is the resource exactly as PDS returned it. A MATCHED decision adds it to the
 * presentation unaltered (FR-DEC-04), so what the EPR is asked to register is what PDS said,
 * rather than this application's reading of it.
 *
 * @param nhsNumber   the resource id
 * @param score       PDS's match score; 1.0 is exact, less is partial
 * @param versionId   from {@code meta.versionId}, so a decision names the version of the record
 *                    it was made against (FR-PDS-11)
 * @param deceased    whether PDS holds a date of death. Shown prominently: it changes the
 *                    conversation entirely
 */
public record PdsCandidate(
        String nhsNumber,
        Double score,
        String family,
        List<String> given,
        String gender,
        String birthDate,
        String postcode,
        List<String> addressLines,
        String gpPracticeCode,
        boolean deceased,
        String deceasedDate,
        String versionId,
        String raw) {

    /** Name as a clinician reads it, for the side-by-side comparison of FR-PDS-07. */
    public String displayName() {
        String givenNames = given == null || given.isEmpty() ? "" : String.join(" ", given);
        String familyName = family == null ? "" : family.toUpperCase();
        return (givenNames + " " + familyName).trim();
    }

    public String displayAddress() {
        List<String> parts = new ArrayList<>();
        if (addressLines != null) {
            parts.addAll(addressLines);
        }
        if (postcode != null && !postcode.isBlank()) {
            parts.add(postcode);
        }
        return String.join(", ", parts);
    }

    /** An exact match on what was searched. Evidence for a decision, not the decision. */
    public boolean exactScore() {
        return score != null && score >= 1.0d;
    }

    /**
     * Reads one {@code Bundle.entry}. Tolerant of absence throughout: PDS returns what it holds,
     * and a patient with no address on file is a normal record rather than a parse failure.
     */
    public static PdsCandidate fromEntry(JsonNode entry, String rawResource) {
        JsonNode resource = child(entry, "resource");
        JsonNode search = child(entry, "search");

        Double score = null;
        JsonNode scoreNode = child(search, "score");
        if (scoreNode != null && scoreNode.isNumber()) {
            score = scoreNode.asDouble();
        }

        JsonNode name = firstOf(child(resource, "name"));
        String family = text(name, "family");
        List<String> given = textList(child(name, "given"));

        JsonNode address = firstOf(child(resource, "address"));
        String postcode = text(address, "postalCode");
        List<String> lines = textList(child(address, "line"));

        JsonNode gp = firstOf(child(resource, "generalPractitioner"));
        String gpCode = text(child(gp, "identifier"), "value");

        String deceasedDate = text(resource, "deceasedDateTime");
        JsonNode deceasedBoolean = child(resource, "deceasedBoolean");
        boolean deceased = deceasedDate != null
                || (deceasedBoolean != null && deceasedBoolean.asBoolean(false));

        return new PdsCandidate(
                text(resource, "id"),
                score,
                family,
                given,
                text(resource, "gender"),
                text(resource, "birthDate"),
                postcode,
                lines,
                gpCode,
                deceased,
                deceasedDate,
                text(child(resource, "meta"), "versionId"),
                rawResource);
    }

    private static JsonNode child(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value;
    }

    private static JsonNode firstOf(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return null;
        }
        return array.get(0);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = child(node, field);
        if (value == null || !value.isValueNode()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static List<String> textList(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode value : array) {
                if (value.isValueNode() && !value.asText().isBlank()) {
                    values.add(value.asText());
                }
            }
        }
        return List.copyOf(values);
    }
}
