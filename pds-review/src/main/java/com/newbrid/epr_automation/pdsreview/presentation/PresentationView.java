package com.newbrid.epr_automation.pdsreview.presentation;

import tools.jackson.databind.JsonNode;
import com.newbrid.epr_automation.pdsreview.domain.UnmatchedReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A read-only window onto one presentation, over the JSON as it arrived.
 *
 * <p>This is the whole of FR-INT-04 in one class. The presentation is a nine-hundred-line schema
 * generated from somebody else's Mongoose models, and this application needs about a dozen fields
 * from it. Binding the lot into records here would mean an upstream model change breaking the
 * review queue, and the post-back has to hand the payload onward unaltered anyway — so the JSON
 * is the stored form, and this class reads what review needs and nothing else.
 *
 * <p>Every accessor tolerates absence. That is not defensiveness for its own sake: the captured
 * samples show `user.nhs` missing entirely, `odsCode` null and `pValue` present but not always
 * meaningful, so "the field is not there" is the normal case rather than the exception.
 */
public final class PresentationView {

    private final JsonNode root;
    private final JsonNode externalValue;

    public PresentationView(JsonNode root) {
        this.root = root == null ? null : root;
        this.externalValue = path(root, "externalValue");
    }

    // ---- why it is here (FR-INT-01) --------------------------------------------------

    /**
     * Whether this presentation carries no PDS demographics, and so needs a human.
     *
     * <p>Two conditions, either of which is enough: {@code nhsUser} absent or null, or
     * {@code user.nhs.status} anything other than {@code successful}. Expressed here, once, so
     * that why an item is in the queue is never re-derived somewhere else with a slightly
     * different rule.
     */
    public boolean unmatched() {
        return nhsUserIsEmpty() || !"successful".equals(nhsStatus().orElse(null));
    }

    private boolean nhsUserIsEmpty() {
        JsonNode nhsUser = path(externalValue, "nhsUser");
        return nhsUser == null || nhsUser.isNull() || nhsUser.isEmpty();
    }

    /** {@code user.nhs.status}, when the sender included the block at all. */
    public Optional<String> nhsStatus() {
        return text(path(path(externalValue, "user"), "nhs"), "status");
    }

    /**
     * Which of the three unmatched cases this is (§2.2). {@code NOT_STATED} is not a fallback for
     * tidiness — it is the commonest case in the captured samples and it is genuinely weaker
     * information than either of the others, because nothing is known about whether PDS was ever
     * asked.
     */
    public UnmatchedReason unmatchedReason() {
        return switch (nhsStatus().orElse("")) {
            case "unsuccessful" -> UnmatchedReason.PDS_LOOKUP_UNSUCCESSFUL;
            case "unavailable" -> UnmatchedReason.PDS_UNAVAILABLE;
            default -> UnmatchedReason.NOT_STATED;
        };
    }

    // ---- identity and ordering -------------------------------------------------------

    /** {@code user._id} — the triage session, and the stable identity of this presentation. */
    public Optional<String> sessionId() {
        return text(path(externalValue, "user"), "_id");
    }

    /** {@code clinicalPath._id}. Present in the sample filenames, so useful for reconciling. */
    public Optional<String> clinicalPathId() {
        return text(path(externalValue, "clinicalPath"), "_id");
    }

    /** {@code user.pValue} — P0 (most urgent) to P5. Absent is a real and frequent answer. */
    public Optional<String> triageScore() {
        return text(path(externalValue, "user"), "pValue");
    }

    /** When the patient finished triage. The clock the ED actually cares about (FR-WL-03). */
    public Optional<String> triageEndTimestamp() {
        return text(path(externalValue, "user"), "endTimestamp");
    }

    public Optional<String> triageStartTimestamp() {
        return text(path(externalValue, "user"), "startTimestamp");
    }

    // ---- context shown on the worklist and review screen -----------------------------

    public Optional<String> hospitalName() {
        return text(path(path(externalValue, "user"), "hospitalId"), "name");
    }

    /** The ODS code an EPR would recognise. Null in every captured sample — see §4.1. */
    public Optional<String> hospitalOdsCode() {
        return text(path(path(externalValue, "user"), "hospitalId"), "odsCode");
    }

    public Optional<String> departmentCode() {
        return text(path(path(externalValue, "routing"), "department"), "departmentCode");
    }

    public Optional<String> departmentName() {
        return text(path(path(externalValue, "routing"), "department"), "departmentName");
    }

    public Optional<String> routingMessage() {
        return text(path(externalValue, "routing"), "message");
    }

    /** The presenting complaint, for the clinical context of FR-REV-07. */
    public Optional<String> presentingComplaint() {
        JsonNode disease = path(path(externalValue, "clinicalPath"), "disease");
        JsonNode names = path(disease, "multiLanguageDiseaseName");
        if (names != null && names.isArray()) {
            for (JsonNode name : names) {
                if ("en".equals(text(name, "language").orElse(null))) {
                    return text(name, "content");
                }
            }
        }
        return text(path(disease, "question"), "description");
    }

    public Optional<String> snomedCode() {
        JsonNode code = path(path(path(externalValue, "clinicalPath"), "disease"), "snomedCode");
        return code == null || code.isNull() ? Optional.empty() : Optional.of(code.asText());
    }

    /** Which wording the patient was given — first person, paediatric, or a proxy answering. */
    public Optional<String> triageVersion() {
        return text(path(externalValue, "user"), "version");
    }

    public boolean incomplete() {
        JsonNode flag = path(path(path(externalValue, "user"), "completion"), "isIncomplete");
        return flag != null && flag.asBoolean(false);
    }

    // ---- the patient's own answers ---------------------------------------------------

    /**
     * {@code user.data[]} — everything the patient typed or picked. This is where the demographics
     * live, and the only place they live, which is what makes the mapping in
     * {@link DataItemMapping} the most fragile thing in the application.
     */
    public List<PatientAnswer> answers() {
        List<PatientAnswer> answers = new ArrayList<>();
        JsonNode data = path(path(externalValue, "user"), "data");
        if (data == null || !data.isArray()) {
            return answers;
        }
        for (JsonNode item : data) {
            answers.add(new PatientAnswer(
                    text(item, "question").orElse(""),
                    text(item, "answer").orElse(""),
                    text(item, "key").orElse(""),
                    text(item, "type").orElse(""),
                    text(item, "id").orElse("")));
        }
        return answers;
    }

    /** The triage pass. Disclosed only on request — it is bulk patient data (FR-REV-06). */
    public Optional<String> pdfBase64() {
        return text(externalValue, "pdfBase64").filter(pdf -> !pdf.isBlank());
    }

    public boolean hasPdf() {
        return pdfBase64().isPresent();
    }

    public JsonNode root() {
        return root;
    }

    // ---- null-tolerant navigation ----------------------------------------------------

    private static JsonNode path(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child;
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode child = path(node, field);
        if (child == null || !child.isValueNode()) {
            return Optional.empty();
        }
        String value = child.asText();
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
