package com.newbrid.epr_automation.pdsreview.ods;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A GP practice as ODS holds it — an organisation with the Prescribing Cost Centre role.
 *
 * @param odsCode the code that goes to PDS as {@code general-practitioner} (FR-ODS-01)
 * @param active  whether the record is in current use. Inactive practices are findable on
 *                purpose: a patient may name one that has since closed or merged (FR-ODS-05)
 */
public record GpPractice(String odsCode, String name, List<String> addressLines,
                         String postcode, String city, boolean active) {

    public String displayAddress() {
        List<String> parts = new ArrayList<>(addressLines == null ? List.of() : addressLines);
        if (city != null && !city.isBlank()) {
            parts.add(city);
        }
        if (postcode != null && !postcode.isBlank()) {
            parts.add(postcode);
        }
        return String.join(", ", parts);
    }

    static GpPractice fromResource(JsonNode resource) {
        JsonNode address = first(resource == null ? null : resource.get("address"));
        JsonNode activeNode = resource == null ? null : resource.get("active");
        return new GpPractice(
                text(resource, "id"),
                text(resource, "name"),
                lines(address),
                text(address, "postalCode"),
                text(address, "city"),
                activeNode == null || activeNode.asBoolean(true));
    }

    private static JsonNode first(JsonNode array) {
        return array != null && array.isArray() && !array.isEmpty() ? array.get(0) : null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isValueNode()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static List<String> lines(JsonNode address) {
        List<String> lines = new ArrayList<>();
        if (address != null) {
            JsonNode line = address.get("line");
            if (line != null && line.isArray()) {
                for (JsonNode value : line) {
                    if (value.isValueNode() && !value.asText().isBlank()) {
                        lines.add(value.asText());
                    }
                }
            }
        }
        return List.copyOf(lines);
    }
}
