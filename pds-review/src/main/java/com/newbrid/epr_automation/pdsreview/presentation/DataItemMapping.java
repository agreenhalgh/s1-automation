package com.newbrid.epr_automation.pdsreview.presentation;

import com.newbrid.epr_automation.pdsreview.pds.PostcodeNormaliser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the patient's answers into PDS search parameters, using a mapping held as data.
 *
 * <p>This is the join that FR-PDS-03 makes configuration rather than code, and §4.2 and R1 explain
 * why: the sender populates the stable {@code key} for almost nothing, so free-text question
 * wording chosen by somebody else's form configuration is the usual handle. A wording change
 * upstream removes a search parameter, and there is no build that will fail to tell us.
 *
 * <p>What contains that is the {@link Derived#missingRequired()} list. When family name, gender or
 * date of birth cannot be derived, the caller does not get a search with two parameters out of
 * three — it gets a named list of what is missing, and no search is issued (FR-PDS-04). A search
 * missing a required field would return a candidate list that answers a different question, and a
 * reviewer has no way to see that from the results.
 */
@Component
public class DataItemMapping {

    private static final Logger log = LoggerFactory.getLogger(DataItemMapping.class);

    /** PDS requires all three of these for a non-fuzzy search. */
    public static final List<String> REQUIRED_FOR_NON_FUZZY = List.of("family", "gender", "birthdate");

    private static final DateTimeFormatter UK_SLASHES = DateTimeFormatter.ofPattern("dd/MM/uuuu");

    private final int version;
    private final Map<String, FieldRule> fields;
    private final Map<String, String> genderValues;

    public DataItemMapping(ResourceLoader resourceLoader,
                           @Value("${systmone.pds-review.mapping.resource:classpath:data-item-mapping.yaml}")
                           String location) {
        Resource resource = resourceLoader.getResource(location);
        Map<String, Object> document = load(resource, location);
        this.version = ((Number) document.getOrDefault("version", 0)).intValue();
        this.fields = readFields(document);
        this.genderValues = readGenderValues(document);
        log.info("Loaded data-item mapping version {} from {}: {} fields, {} gender values",
                version, location, fields.size(), genderValues.size());
    }

    /**
     * One field's rule: the stable keys to prefer, and the question wordings to fall back on.
     *
     * @param keys      matched exactly, ignoring case
     * @param questions matched with case, surrounding whitespace, repeated spaces and a trailing
     *                  {@code ?} or {@code :} ignored — a question the sender re-punctuates should
     *                  not silently stop matching
     */
    public record FieldRule(List<String> keys, List<String> questions) {}

    /**
     * What could be derived, and what could not.
     *
     * @param values          field name to derived value, for the fields that resolved
     * @param submitted       field name to the raw answer, so the screen can show what the patient
     *                        actually wrote next to what will be sent
     * @param postcode        the normalisation attempt, usable or not (FR-PDS-05a/05b)
     * @param missingRequired which of family/gender/birthdate could not be derived. Empty means a
     *                        non-fuzzy search can be issued
     * @param notes           why a field did not resolve — an unparseable date, a gender value not
     *                        in the value set — for the reviewer, not for a log
     */
    public record Derived(Map<String, String> values,
                          Map<String, String> submitted,
                          PostcodeNormaliser.Result postcode,
                          List<String> missingRequired,
                          Map<String, String> notes) {

        public Optional<String> value(String field) {
            return Optional.ofNullable(values.get(field));
        }

        public String familyName() { return values.get("family"); }
        public String givenName() { return values.get("given"); }
        public String gender() { return values.get("gender"); }
        public String birthdate() { return values.get("birthdate"); }
        public String gpPracticeText() { return values.get("gpPractice"); }

        /** Whether PDS's mandatory non-fuzzy combination can be satisfied (FR-PDS-04). */
        public boolean canSearchNonFuzzy() {
            return missingRequired.isEmpty();
        }
    }

    /**
     * Derives what it can from the patient's answers. Never throws and never guesses: a field it
     * cannot resolve is simply absent, with a note saying why.
     */
    public Derived derive(List<PatientAnswer> answers) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> submitted = new LinkedHashMap<>();
        Map<String, String> notes = new LinkedHashMap<>();

        for (Map.Entry<String, FieldRule> field : fields.entrySet()) {
            String name = field.getKey();
            findAnswer(answers, field.getValue()).ifPresent(answer -> {
                submitted.put(name, answer);
                switch (name) {
                    case "gender" -> {
                        String mapped = genderValues.get(normalise(answer));
                        if (mapped == null) {
                            notes.put(name, "'" + answer + "' is not a value PDS recognises for gender");
                        } else {
                            values.put(name, mapped);
                        }
                    }
                    case "birthdate" -> parseDate(answer).ifPresentOrElse(
                            date -> values.put(name, date.toString()),
                            () -> notes.put(name, "'" + answer + "' is not a date PDS can be searched on"));
                    case "postcode" -> { /* normalised below, from `submitted` */ }
                    default -> values.put(name, answer.trim());
                }
            });
        }

        PostcodeNormaliser.Result postcode = PostcodeNormaliser.normalise(submitted.get("postcode"));
        if (postcode.usable()) {
            values.put("postcode", postcode.normalised());
        } else if (submitted.containsKey("postcode")) {
            notes.put("postcode", postcode.reason());
        }

        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_FOR_NON_FUZZY) {
            if (!values.containsKey(required)) {
                missing.add(required);
            }
        }
        return new Derived(values, submitted, postcode, List.copyOf(missing), notes);
    }

    /** The stable key wins over question text wherever the sender bothered to populate one. */
    private Optional<String> findAnswer(List<PatientAnswer> answers, FieldRule rule) {
        for (String key : rule.keys()) {
            for (PatientAnswer answer : answers) {
                if (answer.hasKey(key) && answer.answered()) {
                    return Optional.of(answer.answer());
                }
            }
        }
        for (String question : rule.questions()) {
            String wanted = normalise(question);
            for (PatientAnswer answer : answers) {
                if (wanted.equals(normalise(answer.question())) && answer.answered()) {
                    return Optional.of(answer.answer());
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<LocalDate> parseDate(String value) {
        String trimmed = value.trim();
        try {
            return Optional.of(LocalDate.parse(trimmed));
        } catch (DateTimeParseException ignored) {
            // The date input gives ISO, but a text field will not, and a patient's own hand is
            // exactly where dd/MM/yyyy turns up. Two formats, then give up rather than guess
            // between an ambiguous day and month.
        }
        try {
            return Optional.of(LocalDate.parse(trimmed, UK_SLASHES));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    /** Case, surrounding space, repeated spaces and a trailing {@code ?}/{@code :} all ignored. */
    static String normalise(String text) {
        if (text == null) {
            return "";
        }
        String collapsed = text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.UK);
        while (collapsed.endsWith("?") || collapsed.endsWith(":")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1).trim();
        }
        return collapsed;
    }

    public int version() {
        return version;
    }

    public List<String> mappedFields() {
        return List.copyOf(fields.keySet());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(Resource resource, String location) {
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> document = new Yaml().load(in);
            if (document == null) {
                throw new IllegalStateException("Data-item mapping at " + location + " is empty");
            }
            return document;
        } catch (IOException e) {
            // Starting without the mapping would mean every search silently missing its
            // parameters, which is the failure this file exists to make impossible. Fail loudly.
            throw new IllegalStateException("Cannot read the data-item mapping at " + location, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, FieldRule> readFields(Map<String, Object> document) {
        Map<String, Object> raw = (Map<String, Object>) document.get("fields");
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("The data-item mapping declares no fields");
        }
        Map<String, FieldRule> rules = new LinkedHashMap<>();
        raw.forEach((field, value) -> {
            Map<String, Object> entry = (Map<String, Object>) value;
            rules.put(field, new FieldRule(
                    List.copyOf((List<String>) entry.getOrDefault("keys", List.of())),
                    List.copyOf((List<String>) entry.getOrDefault("questions", List.of()))));
        });
        return Map.copyOf(rules);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> readGenderValues(Map<String, Object> document) {
        Map<String, Object> raw = (Map<String, Object>) document.get("genderValues");
        if (raw == null) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        raw.forEach((answer, pdsValue) -> values.put(normalise(answer), String.valueOf(pdsValue)));
        return Map.copyOf(values);
    }
}
