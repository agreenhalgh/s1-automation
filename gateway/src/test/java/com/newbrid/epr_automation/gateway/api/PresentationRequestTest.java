package com.newbrid.epr_automation.gateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Presentation} is a nine-hundred-line schema expressed as records, and a hand-checked
 * mapping that size is not checkable by reading it. So the samples check it: every captured
 * payload is bound and then written back out, and every value that went in has to come out under
 * the same name. A field this record misspells, omits, or types wrongly cannot survive that.
 *
 * <p>Both trees are canonicalised before comparing, for two differences that are not defects.
 * Null-valued properties are dropped: Jackson cannot tell a property the sender omitted from one
 * they sent as null, and the payloads use both — {@code nhs} is absent, {@code nhsUser} is
 * explicitly null — so a round trip legitimately turns the first into the second. Integral numbers
 * are widened: {@link JsonNode} equality is type-sensitive, so the {@code snomedCode} that was
 * read as an {@code int} would not equal the same value written back out from a {@code Long}. What
 * is left is what matters — that no value is lost or renamed.
 */
class PresentationRequestTest {

    /** The captured payloads: eight triage sessions from one ED, one file each. */
    private static final String[] SAMPLES = {
            "post-6a3c033648aa4975ddfdd461.json",
            "post-6a3c040e48aa4975ddfdd463.json",
            "post-6a3c04d448aa4975ddfdd465.json",
            "post-6a3c05a948aa4975ddfdd467.json",
            "post-6a3c066548aa4975ddfdd469.json",
            "post-6a3c072b48aa4975ddfdd46b.json",
            "post-6a3c07f048aa4975ddfdd46d.json",
            "post-6a3c08a948aa4975ddfdd46f.json",
    };

    private final ObjectMapper mapper = new ObjectMapper();

    // ---- the mapping is complete -----------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "post-6a3c033648aa4975ddfdd461.json",
            "post-6a3c040e48aa4975ddfdd463.json",
            "post-6a3c04d448aa4975ddfdd465.json",
            "post-6a3c05a948aa4975ddfdd467.json",
            "post-6a3c066548aa4975ddfdd469.json",
            "post-6a3c072b48aa4975ddfdd46b.json",
            "post-6a3c07f048aa4975ddfdd46d.json",
            "post-6a3c08a948aa4975ddfdd46f.json",
    })
    void bindsEverySampleWithoutLosingAValue(String sample) throws IOException {
        JsonNode original = read(sample);

        PresentationRequest bound = mapper.treeToValue(original, PresentationRequest.class);
        JsonNode roundTripped = mapper.valueToTree(bound);

        assertThat(canonical(roundTripped))
                .as("every value in %s must survive binding under the same name", sample)
                .isEqualTo(canonical(original));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "post-6a3c033648aa4975ddfdd461.json",
            "post-6a3c08a948aa4975ddfdd46f.json",
    })
    void realPayloadsSatisfyTheConstraints(String sample) throws IOException {
        PresentationRequest bound = mapper.treeToValue(read(sample), PresentationRequest.class);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(bound))
                    .as("a captured payload must not trip a constraint this record invented")
                    .isEmpty();
        }
    }

    // ---- the mappings a reader would get wrong ---------------------------------------

    @Test
    void mapsTheNamesThatCannotBeSpeltInJava() throws IOException {
        Presentation presentation = load("post-6a3c08a948aa4975ddfdd46f.json").externalValue();

        assertThat(presentation.user().id())
                .as("_id is not a legal Java identifier, so it becomes id")
                .isEqualTo("6a3c07f79aa78a4853b67c15");
        assertThat(presentation.clinicalPath().dynamicUserId())
                .as("the pathway's back-reference must resolve to the session it came with")
                .isEqualTo(presentation.user().id());

        Presentation.AnswerOption answer =
                presentation.clinicalPath().record().getFirst().answers().getFirst();
        assertThat(answer.id())
                .as("_id: the sub-document Mongoose stamped on the answer")
                .isEqualTo("6a3c08a99aa78a4853b67c63");
        assertThat(answer.optionId())
                .as("id: the option in the question's own definition")
                .isEqualTo("66421b16e43b70e83d598d7a");
    }

    @Test
    void readsTheClinicalFieldsAConsumerActuallyNeeds() throws IOException {
        Presentation presentation = load("post-6a3c08a948aa4975ddfdd46f.json").externalValue();

        assertThat(presentation.clinicalPath().disease().snomedCode()).isEqualTo(230145002L);
        assertThat(presentation.clinicalPath().disease().injuryOrIllness())
                .isEqualTo(Presentation.InjuryOrIllness.ILLNESS);
        assertThat(presentation.clinicalPath().disease().sexAtBirth())
                .isEqualTo(Presentation.SexAtBirth.GENERAL);
        assertThat(presentation.user().version())
                .isEqualTo(Presentation.TriageVersion.FIRST_PERSON);
        assertThat(presentation.user().pValue()).isEqualTo("P5");
        assertThat(presentation.routing().department().departmentCode()).isEqualTo("ED");
        assertThat(presentation.user().hospitalId().code()).isEqualTo(797_283L);
        assertThat(presentation.pdfBase64()).startsWith("JVBERi0x");

        Map<String, String> answersByQuestion = new java.util.LinkedHashMap<>();
        presentation.user().data()
                .forEach(item -> answersByQuestion.put(item.question(), item.answer()));
        assertThat(answersByQuestion)
                .as("the demographics an EPR needs live in user.data, keyed by question text")
                .containsEntry("First Name", "Ava")
                .containsEntry("Date of Birth", "1947-10-13");
    }

    @Test
    void distinguishesAnAbsentSectionFromANamedOne() throws IOException {
        Presentation presentation = load("post-6a3c08a948aa4975ddfdd46f.json").externalValue();

        List<String> sections = presentation.user().data().stream()
                .map(item -> item.pdf().section())
                .toList();

        assertThat(sections)
                .as("a nullable string, not a default: null means the answer prints nowhere")
                .contains("pds", (String) null);
    }

    @Test
    void carriesTheSchemalessBlocksWithoutAssertingAShape() throws IOException {
        Presentation presentation = load("post-6a3c08a948aa4975ddfdd46f.json").externalValue();

        assertThat(presentation.nhsUser())
                .as("sent as an explicit JSON null, which must not arrive as a truthy NullNode")
                .isNull();
        assertThat(presentation.user().nhs())
                .as("omitted entirely by this sender, which is not the same as an empty object")
                .isNull();
    }

    // ---- what the record refuses ------------------------------------------------------

    @Test
    void rejectsAFieldItDoesNotKnow() {
        String body = """
                {
                  "summary": "OPTO Health",
                  "externalValue": {
                    "clinicalPath": null,
                    "ecdsPathway": [],
                    "isolation": [],
                    "publicHealth": [],
                    "nhsUser": null,
                    "nhsUserFlagsCPIS": null,
                    "nhsUserFlagsNRAF": null,
                    "pdfBase64": "",
                    "user": { "_id": "6a3c08a948aa4975ddfdd46f", "triageOutcome": "admitted" }
                  }
                }
                """;

        assertThatThrownBy(() -> mapper.readValue(body, PresentationRequest.class))
                .as("an unrecognised field in a clinical payload is a contract change, not padding")
                .hasMessageContaining("triageOutcome");
    }

    @Test
    void rejectsAValueOutsideAClosedSet() {
        String body = """
                {
                  "summary": "OPTO Health",
                  "externalValue": {
                    "clinicalPath": null,
                    "ecdsPathway": [],
                    "isolation": [],
                    "publicHealth": [],
                    "nhsUser": null,
                    "nhsUserFlagsCPIS": null,
                    "nhsUserFlagsNRAF": null,
                    "pdfBase64": "",
                    "user": { "_id": "6a3c08a948aa4975ddfdd46f", "version": "thirdPerson" }
                  }
                }
                """;

        assertThatThrownBy(() -> mapper.readValue(body, PresentationRequest.class))
                .as("who was answering decides how the answers read, so an unknown wording fails")
                .hasMessageContaining("thirdPerson");
    }

    @Test
    void namesTheStructuralFieldsThatAreMissing() throws IOException {
        PresentationRequest stripped = new PresentationRequest("Not OPTO Health",
                new Presentation(null, null, null, null, null, null, null, null, null, null));

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(stripped))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("summary", "externalValue.ecdsPathway",
                            "externalValue.isolation", "externalValue.publicHealth",
                            "externalValue.pdfBase64", "externalValue.user");
        }
    }

    // ---- helpers ----------------------------------------------------------------------

    /** Guards against a sample being renamed out from under the parameterised tests. */
    @Test
    void everySampleIsExercised() {
        assertThat(SAMPLES).hasSize(8).allSatisfy(sample ->
                assertThat(getClass().getResourceAsStream("/" + sample))
                        .as("%s must be on the test classpath", sample)
                        .isNotNull());
    }

    private PresentationRequest load(String sample) throws IOException {
        return mapper.treeToValue(read(sample), PresentationRequest.class);
    }

    private JsonNode read(String sample) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/" + sample)) {
            assertThat(in).as("sample %s is on the test classpath", sample).isNotNull();
            return mapper.readTree(in);
        }
    }

    /**
     * The same tree with null-valued properties removed and integral numbers widened to long, at
     * any depth — the two differences a faithful round trip still produces. See the class comment.
     */
    private static JsonNode canonical(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode copy = object.objectNode();
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = object.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
            names.stream()
                    .filter(name -> !object.get(name).isNull())
                    .forEach(name -> copy.set(name, canonical(object.get(name))));
            return copy;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = array.arrayNode();
            array.forEach(element -> copy.add(canonical(element)));
            return copy;
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return LongNode.valueOf(node.longValue());
        }
        return node;
    }
}
