package com.newbrid.epr_automation.gateway.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A completed triage presentation as OPTO Health sends it: the clinical pathway the patient was
 * taken down, the ECDS answers, isolation and public-health screening, NHS demographics and
 * flags, the department they were routed to, and the triage-pass PDF.
 *
 * <h2>Why it mirrors the source schema one for one</h2>
 * Every nested record here corresponds to exactly one {@code $defs} entry in
 * {@code docs/answer_digital-presentation.schema.json}, in the same order, with the same field
 * names. That is deliberate and worth preserving: the schema is generated from OPTO Health's
 * Mongoose models, so it will change when their models do, and a reader holding the two side by
 * side needs to be able to diff them. A tidier, re-grouped Java model would be nicer to look at
 * once and much harder to keep honest.
 *
 * <p>The records are flat rather than nested inside one another for the same reason — the source
 * has no hierarchy of definitions, and inventing one would put {@code Disease} somewhere a reader
 * would have to go hunting for it.
 *
 * <h2>Names</h2>
 * Mongoose stamps {@code _id} on every document and array sub-document. That is not a legal Java
 * identifier, so it is mapped to {@code id} throughout, as {@link RunSubmission.User} already
 * does. Two types carry <em>both</em> {@code _id} and a separate {@code id}, and there the second
 * is named after what it actually points at — {@link AnswerOption#optionId()} and
 * {@link QuestionDetails#questionId()} — which also lines them up with the
 * {@code optionId}/{@code questionId} fields that reference them elsewhere in the payload.
 *
 * <h2>Types</h2>
 * <ul>
 *   <li><b>Mongoose {@code Mixed} fields are {@link JsonNode}.</b> The NHS PDS demographics and
 *       the CPIS/NRAF flag blocks are schema-less at source — the schema says only "Mixed or
 *       null". Binding them to {@code Map<String, Object>} would assert a shape nobody has
 *       specified; {@code JsonNode} carries whatever arrives, unaltered, which is what a gateway
 *       that passes them on needs. They are null when there is nothing there: Jackson hands a
 *       {@code JsonNode} property an explicit JSON null as {@code NullNode} rather than as Java
 *       null, and the constructors below flatten that, so a consumer has one absence check to
 *       make instead of two.</li>
 *   <li><b>Timestamps are strings.</b> Same reasoning as {@link RunResponse}: they arrive as
 *       ISO-8601 UTC and leave as ISO-8601 UTC, and parsing them here only to render them again
 *       would create a second place for a time zone to be applied by accident. It also keeps the
 *       record bindable by a Jackson mapper with no JSR-310 module registered, which is what
 *       Camel's {@code json(JsonLibrary.Jackson)} data format gives you by default.</li>
 *   <li><b>Every scalar is a wrapper type.</b> {@code Boolean} not {@code boolean},
 *       {@code Integer} not {@code int}. The schema documents defaults for most of these
 *       ({@code show: false}, {@code order: 0}, {@code isActive: true}) and a primitive would
 *       silently manufacture one, making "the sender omitted this" indistinguishable from "the
 *       sender said false". On a clinical record that distinction is the whole point.</li>
 * </ul>
 *
 * <h2>Unknown fields are rejected</h2>
 * The source schema is closed — {@code additionalProperties: false} on every definition — so a
 * field this record does not know about is a change to OPTO Health's models rather than harmless
 * padding, and quietly dropping part of a clinical payload is the worse of the two failures. The
 * same argument as on {@link RunSubmission}, and the same consequence: the sender gets told,
 * rather than a patient being presented with a field nobody read.
 *
 * <h2>What this record does not check</h2>
 * The constraints here are structural — the fields the schema marks required and non-nullable,
 * and the object-id shape on the identifiers a consumer actually dereferences. The business rules
 * behind the endpoint's 422 codes (modulus-11 on the NHS number, a date of birth in the future, a
 * postcode's format) are not expressible as annotations on this shape, because their subjects are
 * free-text answers inside {@link User#data()} keyed by {@code key}/{@code question} rather than
 * typed fields. Those belong in a validation step that reads the answers, alongside the
 * modulus-11 check {@code SubmissionTransformer} already performs for {@link RunSubmission}.
 *
 * <p>Deep sub-document {@code _id}s are deliberately left unconstrained: Mongoose generates them,
 * nothing downstream dereferences them, and twenty more patterns would add cost to every request
 * to police values no one reads.
 *
 * @param clinicalPath     the pathway the patient was triaged down. Required by the schema but
 *                         nullable — a session can end before a pathway is chosen
 * @param ecdsPathway      Emergency Care Data Set answers
 * @param isolation        isolation screening answers
 * @param publicHealth     public-health screening answers
 * @param nhsUser          NHS PDS demographics for the patient, or null when the lookup did not
 *                         succeed. See {@link Nhs#status()}
 * @param nhsUserFlagsCPIS Child Protection Information Sharing flags, or null
 * @param nhsUserFlagsNRAF National Reasonable Adjustment Flag data, or null
 * @param pdfBase64        the triage pass, base64-encoded. Empty string when generation produced
 *                         no file — which is not the same as absent, and is why this is not
 *                         checked for blankness
 * @param routing          where the patient was sent. Absent from the JSON entirely when the
 *                         session was never routed
 * @param user             the triage session and the patient's own answers
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Presentation(

        @Valid
        ClinicalPath clinicalPath,

        @NotNull(message = "ecdsPathway is required")
        List<@Valid EcdsRecordItem> ecdsPathway,

        @Null
        List<@Valid ScreeningItem> isolation,

        @Null
        List<@Valid ScreeningItem> publicHealth,

        JsonNode nhsUser,

        JsonNode nhsUserFlagsCPIS,

        JsonNode nhsUserFlagsNRAF,

        /*
         * Bounded for the same reason as RunSubmission.pdfBase64: it is the one field that can
         * make a request arbitrarily large, and base64 costs a third on top of the bytes it
         * encodes. A backstop only — by the time a constraint can run the body is already parsed
         * and in memory, so a hard limit belongs at the container or reverse proxy.
         */
        @NotNull(message = "pdfBase64 is required")
        @Size(max = 20_971_520, message = "pdfBase64 exceeds the maximum accepted size")
        String pdfBase64,

        @Valid
        Routing routing,

        @NotNull(message = "user is required")
        @Valid
        User user) {

    public Presentation {
        nhsUser = absentIfNull(nhsUser);
        nhsUserFlagsCPIS = absentIfNull(nhsUserFlagsCPIS);
        nhsUserFlagsNRAF = absentIfNull(nhsUserFlagsNRAF);
    }

    /** 24 hex characters, which is how a Mongo ObjectId serialises. */
    static final String OBJECT_ID = "[a-fA-F0-9]{24}";

    /**
     * Java null for a JSON null, so that "the sender had nothing to give" is one condition rather
     * than two. Jackson binds an explicit {@code null} to a {@code JsonNode} property as
     * {@code NullNode}, which is truthy in Java and reads as absent in a debugger — a consumer
     * writing the obvious {@code if (nhsUser() != null)} would go on to read demographics that
     * are not there.
     */
    private static JsonNode absentIfNull(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    /**
     * The pathway the patient was triaged down, and every question they were asked on it.
     *
     * @param dynamicUserId back-reference to {@link User#id()} — the same session, from the other
     *                      end of the relation
     * @param version       which wording of the pathway was used. A free string here, unlike
     *                      {@link User#version()}, because the source schema does not close the
     *                      set at this position even though the observed values are the same
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ClinicalPath(

            @JsonProperty("_id")
            @Pattern(regexp = OBJECT_ID, message = "clinicalPath._id must be a 24-character hex id")
            String id,

            @Valid
            Disease disease,

            @Pattern(regexp = OBJECT_ID,
                    message = "clinicalPath.dynamicUserId must be a 24-character hex id")
            String dynamicUserId,

            String version,

            List<@Valid ClinicalRecordItem> record) {}

    /**
     * One question and its answer on the clinical pathway.
     *
     * <p>Both {@code answer} and {@code answers} exist because the same record serves single- and
     * multi-option questions; only the latter is populated in the sampled payloads, where the
     * chosen options arrive as {@link AnswerOption} entries carrying their own content. The
     * singular form is kept because the source schema declares it, and a field that turns up
     * later should bind rather than bounce.
     *
     * @param code               the priority this answer carries, {@code P0} (most urgent)
     *                           through {@code P5}. A free string in the schema, so not an enum
     *                           here — see {@link User#pValue()}
     * @param nextQuestionNumber where the answer sent the patient next, which is what makes the
     *                           record a traversable path rather than a flat list
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ClinicalRecordItem(

            @JsonProperty("_id")
            String id,

            String answer,

            List<@Valid AnswerOption> answers,

            String code,

            Integer nextQuestionNumber,

            String question,

            @Valid
            QuestionDetails questionDetails,

            String questionId,

            Integer questionNumber,

            String selectedOptionId) {}

    /**
     * One Emergency Care Data Set question and its answer.
     *
     * @param integrations per-answer hooks for downstream systems, keyed by platform. Empty in
     *                     every sampled payload
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EcdsRecordItem(

            @JsonProperty("_id")
            String id,

            List<@Valid AnswerOption> answers,

            List<@Valid Integration> integrations,

            Integer nextQuestionNumber,

            @Valid
            PdfBlock pdf,

            @Valid
            QuestionDetails questionDetails,

            Integer questionNumber) {}

    /**
     * One isolation or public-health screening answer. Flatter than the pathway records —
     * screening asks a question and records the option picked, with no traversal.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ScreeningItem(

            @JsonProperty("_id")
            String id,

            String option,

            String optionId,

            @Valid
            PdfBlock pdf,

            String question,

            String questionId) {}

    /**
     * An option the patient chose.
     *
     * @param optionId the id of the option in the question's definition — {@code id} on the wire,
     *                 renamed because {@code _id} already claims {@code id} here. Matches
     *                 {@link ClinicalRecordItem#selectedOptionId()} and
     *                 {@link ScreeningItem#optionId()}
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AnswerOption(

            @JsonProperty("_id")
            String id,

            String code,

            String content,

            @JsonProperty("id")
            String optionId) {}

    /**
     * The question as it was put to the patient, denormalised onto the answer so the record still
     * reads correctly after the question definition is edited.
     *
     * @param questionId the question definition's id — {@code id} on the wire, renamed for the
     *                   same reason as {@link AnswerOption#optionId()}
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record QuestionDetails(

            String content,

            @JsonProperty("id")
            String questionId,

            String questionType) {}

    /**
     * Whether and where an answer appears on the triage-pass PDF.
     *
     * @param applyAllConditions whether every condition must hold, or any one of them
     * @param conditions         other answers this one's visibility depends on
     * @param order              position within the section
     * @param section            the section of the PDF to print under, or null for none. Observed
     *                           values include {@code pds} and {@code key_safety}
     * @param show               whether it prints at all
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PdfBlock(

            Boolean applyAllConditions,

            List<@Valid PdfCondition> conditions,

            Integer order,

            String section,

            Boolean show) {}

    /** An answer that must have been given for the owning block to print. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PdfCondition(

            @JsonProperty("_id")
            String id,

            @NotNull(message = "pdf condition option is required")
            String option,

            @NotNull(message = "pdf condition optionId is required")
            String optionId,

            @NotNull(message = "pdf condition question is required")
            String question,

            @NotNull(message = "pdf condition questionId is required")
            String questionId) {}

    /**
     * A downstream system that wants this answer, and the name it wants it under. Both fields are
     * required by the schema: an entry naming a platform but no key, or the reverse, cannot be
     * acted on.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Integration(

            @JsonProperty("_id")
            String id,

            @NotNull(message = "integration key is required")
            String key,

            @NotNull(message = "integration platform is required")
            String platform) {}

    /**
     * One thing the patient typed or picked on a form screen — name, date of birth, phone number,
     * free-text detail. This is where the demographics an EPR needs actually live, which makes it
     * the part of the payload most worth reading carefully.
     *
     * <p>Unlike the pathway records these carry no {@code _id}: they are template fields carried
     * with the session, not sub-documents Mongoose created.
     *
     * @param answer   what the patient gave; empty string when the screen was left blank
     * @param id       client-generated field id, of the form {@code field-<epoch-ms>}
     * @param key      a stable name for the fields a consumer needs to find by meaning rather
     *                 than by question text — {@code dob} in the sampled payloads. Empty for the
     *                 rest, which is why {@code question} is the fallback
     * @param nullable whether the screen allowed an empty answer
     * @param type     the input control used: {@code text}, {@code date}, {@code select},
     *                 {@code radio}, {@code tel}, {@code textarea}. A free string because the
     *                 schema does not close the set, and a new control type appearing should not
     *                 cost a whole presentation
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DataItem(

            String answer,

            String id,

            List<@Valid Integration> integrations,

            String key,

            Boolean nullable,

            @Valid
            PdfBlock pdf,

            String question,

            String type) {}

    /**
     * Where the triage sent the patient.
     *
     * @param message      the instruction shown to the patient, in their words rather than the
     *                     department's
     * @param redirections every subsequent move, oldest first — a patient sent to ED and then
     *                     moved to minors has one entry per move
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Routing(

            @Valid
            Department department,

            String message,

            List<@Valid Redirection> redirections) {}

    /**
     * One move between departments. The departments are bare object ids here, not populated
     * documents — only {@link Routing#department()} is expanded by the sender.
     *
     * @param isHandedOver whether the receiving department accepted the patient, as opposed to
     *                     the move merely having been recorded
     * @param referrer     the clinician who moved them
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Redirection(

            @JsonProperty("_id")
            String id,

            @Pattern(regexp = OBJECT_ID,
                    message = "redirection department must be a 24-character hex id")
            String department,

            Boolean isHandedOver,

            String notes,

            @Pattern(regexp = OBJECT_ID,
                    message = "redirection previousDepartment must be a 24-character hex id")
            String previousDepartment,

            @Pattern(regexp = OBJECT_ID,
                    message = "redirection referrer must be a 24-character hex id")
            String referrer,

            String timestamp) {}

    /** The department the patient was routed to. Observed: {@code ED}, Emergency Department. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Department(

            @JsonProperty("_id")
            @Pattern(regexp = OBJECT_ID, message = "department._id must be a 24-character hex id")
            String id,

            String departmentCode,

            String departmentName,

            String description) {}

    /**
     * The site the triage happened at.
     *
     * @param code    the sender's own numeric identifier for the site
     * @param odsCode the NHS Organisation Data Service code, which is the one an EPR will
     *                recognise. Null in every sampled payload, so a consumer cannot rely on it
     *                and has to map {@code code} instead
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Hospital(

            @JsonProperty("_id")
            @Pattern(regexp = OBJECT_ID, message = "hospitalId._id must be a 24-character hex id")
            String id,

            Long code,

            String name,

            String odsCode) {}

    /**
     * The triage session — not a logged-in account. "User" is the sender's word for one anonymous
     * pass through the triage app by one patient, which is why it holds timestamps and answers
     * rather than credentials.
     *
     * @param alerts         schema-less at source, so carried as-is. Empty in every sampled
     *                       payload
     * @param nhs            the result of the PDS lookup for this session. Absent from the JSON
     *                       in every sampled payload — the demographics are repeated at the top
     *                       level as {@link Presentation#nhsUser()}
     * @param pValue         the session's overall priority, {@code P0} (most urgent) through
     *                       {@code P5}. Not an enum: the schema types it as a nullable string,
     *                       and a new grade appearing should not cost a whole presentation
     * @param startTimestamp when the patient began. With {@code endTimestamp} this gives the
     *                       triage duration, which is the number an ED actually watches
     * @param version        which wording of the triage the patient was given
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record User(

            @JsonProperty("_id")
            @Pattern(regexp = OBJECT_ID, message = "user._id must be a 24-character hex id")
            String id,

            List<JsonNode> alerts,

            @Valid
            Completion completion,

            List<@Valid DataItem> data,

            String endTimestamp,

            @Valid
            Hospital hospitalId,

            @Valid
            Nhs nhs,

            String pValue,

            String startTimestamp,

            TriageVersion version) {}

    /**
     * Whether the patient finished. An incomplete session is still sent — someone who abandoned
     * triage in the waiting room has still presented, and the ED needs to know they are there.
     *
     * @param incompleteReason free text, and empty rather than absent on a completed session
     * @param lastScreen       how far they got, or null
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Completion(

            String incompleteAt,

            String incompleteReason,

            Boolean isIncomplete,

            String lastScreen) {}

    /**
     * What the NHS Personal Demographics Service lookup returned for this session.
     *
     * @param cpis   Child Protection Information Sharing flags, or null
     * @param nraf   National Reasonable Adjustment Flag data, or null
     * @param status whether the lookup happened and whether it matched — the field that tells a
     *               consumer whether the demographics beside it can be trusted at all
     * @param user   the matched patient's demographics, or null
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Nhs(

            JsonNode cpis,

            JsonNode nraf,

            NhsLookupStatus status,

            JsonNode user) {

        public Nhs {
            cpis = absentIfNull(cpis);
            nraf = absentIfNull(nraf);
            user = absentIfNull(user);
        }
    }

    /**
     * The presenting complaint the pathway was chosen for, as defined in the sender's catalogue.
     *
     * <p>Most of this is the pathway's own configuration — the combination rules that decide
     * which questions a patient sees — rather than anything about this patient. It arrives because
     * the sender populates the whole document, and is modelled rather than discarded so that a
     * consumer reconstructing why a particular question was asked has the rules to hand.
     *
     * @param snomedCode the SNOMED CT concept id for the complaint, and the only field here an
     *                   EPR is likely to want. Long rather than Integer: SNOMED ids run to 18
     *                   digits, well past what an int holds, even though the observed values are
     *                   short
     * @param tags       free-text search terms the patient's own words were matched against
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Disease(

            @JsonProperty("_id")
            @Pattern(regexp = OBJECT_ID, message = "disease._id must be a 24-character hex id")
            String id,

            InjuryOrIllness injuryOrIllness,

            SexAtBirth sexAtBirth,

            Long snomedCode,

            List<String> tags,

            List<@Valid MultiLanguageText> multiLanguageDiseaseName,

            List<@Valid CombinationGroup> combination,

            List<@Valid AgeCombinationGroup> ageCombination,

            @Valid
            DiseaseQuestion question) {}

    /**
     * The disambiguating question asked when several pathways could fit the complaint, and the
     * pathways it can lead to.
     *
     * <p>Three parallel translations of the same question rather than one keyed by audience: the
     * first-person wording lives in {@code multiLanguageDescription}, with the paediatric and
     * proxy wordings beside it. Which one the patient saw is {@link User#version()}.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DiseaseQuestion(

            String description,

            List<@Valid MultiLanguageText> multiLanguageDescription,

            List<@Valid MultiLanguageText> multiLanguageDescriptionPaediatric,

            List<@Valid MultiLanguageText> multiLanguageDescriptionProxy,

            List<@Valid PathwayOption> options) {}

    /**
     * One pathway the disambiguating question can lead to.
     *
     * @param isCurrentPathway whether this is the one the patient was actually taken down, which
     *                         is what makes the option list a record of the decision rather than
     *                         just of the choices
     * @param pathway          object id of the pathway definition
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PathwayOption(

            @JsonProperty("_id")
            String id,

            Boolean isCurrentPathway,

            List<@Valid MultiLanguageText> multiLanguageOptionDescription,

            String optionDescription,

            @Pattern(regexp = OBJECT_ID, message = "pathway must be a 24-character hex id")
            String pathway,

            String pathwayName,

            String version) {}

    /**
     * A named set of answers that, given together, identify a presentation the pathway handles.
     *
     * @param overAllCode the priority the whole combination carries, which is how a set of
     *                    individually unremarkable answers can still escalate a patient
     * @param isDeleted   soft deletion. A retired combination is still sent, so a consumer
     *                    reading an old session sees the rules as they stood
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CombinationGroup(

            @JsonProperty("_id")
            String id,

            List<@Valid CombinationCriterion> combination,

            String createdAt,

            Boolean isActive,

            Boolean isDeleted,

            String overAllCode,

            String title,

            String version) {}

    /** One question-and-option pair within a {@link CombinationGroup}. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CombinationCriterion(

            @JsonProperty("_id")
            String id,

            String option,

            @Pattern(regexp = OBJECT_ID,
                    message = "combination question must be a 24-character hex id")
            String question) {}

    /**
     * A {@link CombinationGroup} that only applies within an age band — the mechanism behind a
     * symptom meaning something different in an infant than in an adult.
     *
     * @param ageRange schema-less at source, so carried as-is
     * @param isInfant whether the band is the infant one, called out separately from the range
     *                 because it changes the pathway rather than just narrowing it
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AgeCombinationGroup(

            @JsonProperty("_id")
            String id,

            List<JsonNode> ageRange,

            List<@Valid AgeCombinationCriterion> combination,

            String createdAt,

            Boolean isActive,

            Boolean isDeleted,

            Boolean isInfant,

            String overAllCode,

            String title,

            String version) {}

    /**
     * One criterion within an {@link AgeCombinationGroup}. {@code option} is a list here where
     * {@link CombinationCriterion#option()} is a single value — an age-banded rule can be
     * satisfied by any of several answers.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AgeCombinationCriterion(

            @JsonProperty("_id")
            String id,

            List<String> option,

            @Pattern(regexp = OBJECT_ID,
                    message = "age combination question must be a 24-character hex id")
            String question) {}

    /**
     * One translation of one piece of patient-facing text.
     *
     * @param language ISO 639-1 code; {@code en} and {@code ar} appear in the sampled payloads
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MultiLanguageText(

            @JsonProperty("_id")
            String id,

            String content,

            String language) {}

    /**
     * Which wording of the triage the patient was given, and so who was answering: the patient
     * about themselves, an adult about a child, or someone answering on another adult's behalf.
     *
     * <p>An enum, unlike the other {@code version} fields in this payload, because the schema
     * closes the set at this position — and because it changes how the answers should be read.
     * "Are you in pain?" answered by a proxy is not a statement about the person answering.
     */
    public enum TriageVersion {

        /** The patient answering about themselves. */
        @JsonProperty("firstPerson") FIRST_PERSON,

        /** An adult answering about a child. */
        @JsonProperty("paediatric") PAEDIATRIC,

        /** Someone answering on another adult's behalf. */
        @JsonProperty("proxy") PROXY
    }

    /** Whether the presenting complaint is an injury, an illness, or neither. */
    public enum InjuryOrIllness {

        @JsonProperty("Injury") INJURY,

        @JsonProperty("Illness") ILLNESS,

        /** Applies regardless — the pathway is not specific to one or the other. */
        @JsonProperty("General") GENERAL
    }

    /**
     * The sex a pathway applies to. {@code General} means the pathway is not sex-specific, which
     * is a property of the pathway and says nothing about the patient.
     */
    public enum SexAtBirth {

        @JsonProperty("Male") MALE,

        @JsonProperty("Female") FEMALE,

        @JsonProperty("Other") OTHER,

        @JsonProperty("General") GENERAL
    }

    /**
     * How the NHS Personal Demographics Service lookup went. The distinction that matters is
     * between {@link #UNSUCCESSFUL} and {@link #UNAVAILABLE}: the first means PDS was asked and
     * did not match the patient, the second that it was never reachable. Absent demographics mean
     * something different in each case, and only the second is worth retrying.
     */
    public enum NhsLookupStatus {

        /** PDS answered, and no patient matched. */
        @JsonProperty("unsuccessful") UNSUCCESSFUL,

        /** PDS answered and matched the patient. */
        @JsonProperty("successful") SUCCESSFUL,

        /** PDS was not reachable, or was never asked. */
        @JsonProperty("unavailable") UNAVAILABLE
    }
}
