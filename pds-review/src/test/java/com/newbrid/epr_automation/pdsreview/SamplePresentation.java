package com.newbrid.epr_automation.pdsreview;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * A real presentation, captured from the sender, for tests to work against.
 *
 * <p>Deliberately not a fixture built from the schema. The difficulty this application exists to
 * handle is a property of the real payload and not of the schema: the sender populates the stable
 * {@code key} for exactly one field, leaves free-text question wording as the only handle on the
 * rest, sends {@code nhsUser} as null, and omits {@code user.nhs} entirely. A tidy hand-made
 * fixture has none of those properties, and would pass while the real thing failed.
 */
public final class SamplePresentation {

    private static final JsonMapper JSON = new JsonMapper();

    /** The item key this payload admits under — {@code externalValue.user._id}. */
    public static final String SESSION_ID = "6a3c07f79aa78a4853b67c15";

    private SamplePresentation() {}

    /** An unmatched presentation: no PDS demographics, and no record of a lookup at all. */
    public static JsonNode unmatched() throws IOException {
        try (InputStream in = SamplePresentation.class
                .getResourceAsStream("/presentation-unmatched.json")) {
            if (in == null) {
                throw new IllegalStateException(
                        "presentation-unmatched.json is not on the test classpath");
            }
            return JSON.readTree(in);
        }
    }
}
