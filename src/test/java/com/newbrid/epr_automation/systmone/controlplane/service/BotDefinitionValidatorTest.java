package com.newbrid.epr_automation.systmone.controlplane.service;

import com.newbrid.epr_automation.systmone.common.dto.BotDefinition;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.dto.StepDefinition;
import com.newbrid.epr_automation.systmone.common.model.ActionType;
import com.newbrid.epr_automation.systmone.common.model.NameMatch;
import com.newbrid.epr_automation.systmone.controlplane.domain.TemplateEntity;
import com.newbrid.epr_automation.systmone.controlplane.repo.TemplateRepository;
import com.newbrid.epr_automation.systmone.common.dto.TestSteps;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Registration must reject image locators whose templates are not in the registry
 * (FR-REG-04) — otherwise the mistake only appears as a failed step on a worker, after the
 * run has been dispatched and retried.
 */
class BotDefinitionValidatorTest {

    private final TemplateRepository templates = mock(TemplateRepository.class);
    private final BotDefinitionValidator validator = new BotDefinitionValidator(templates);

    private static TemplateEntity template(String name, byte[] data) {
        return new TemplateEntity("id-" + name, name, "2024", 0.9, null, "test", "checksum", data);
    }

    private static TemplateEntity withImage(String name) {
        return template(name, new byte[] {1, 2, 3});
    }

    private static BotDefinition bot(StepDefinition... steps) {
        return new BotDefinition("bot", "Bot", 1, "2024", List.of(steps), 0, null);
    }

    private static StepDefinition step(String name, LocatorDefinition... chain) {
        return TestSteps.step(name, ActionType.CLICK, chain).build();
    }

    /** A step that names itself, so something can jump to it. */
    private static StepDefinition keyed(String key, String name) {
        return TestSteps.step(name, ActionType.CLICK, LocatorDefinition.jab("push button", name, null))
                .key(key).build();
    }

    /** A step that branches to {@code onFound} / {@code onMissing}. */
    private static StepDefinition branching(String name, String onFound, String onMissing) {
        return TestSteps.step(name, ActionType.WAIT, LocatorDefinition.jab("dialog", name, null))
                .onFound(onFound).onMissing(onMissing).build();
    }

    /** A step bounded by maxExecutions, optionally with somewhere to go when it runs out. */
    private static StepDefinition limited(String name, Integer maxExecutions, String onLimit) {
        return TestSteps.step(name, ActionType.CLICK, LocatorDefinition.jab("push button", name, null))
                .maxExecutions(maxExecutions).onLimit(onLimit).build();
    }

    @Test
    void acceptsADefinitionWhoseTemplatesAllExist() {
        when(templates.findByName("search-button")).thenReturn(Optional.of(withImage("search-button")));
        when(templates.findByName("label-nhs")).thenReturn(Optional.of(withImage("label-nhs")));

        assertDoesNotThrow(() -> validator.validate(bot(
                step("Search", LocatorDefinition.image("search-button", 0.92)),
                step("Type", LocatorDefinition.imageAnchored(null, 0.94, "label-nhs", 130, 8)))));
    }

    @Test
    void rejectsAnUnknownTemplateRefNamingStepAndField() {
        when(templates.findByName(anyString())).thenReturn(Optional.empty());

        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(step("Click Search",
                        LocatorDefinition.image("search-buton", 0.92)))));

        assertEquals(1, ex.problems().size());
        String problem = ex.problems().getFirst();
        assertTrue(problem.contains("step 0 'Click Search' locator 0"), problem);
        assertTrue(problem.contains("templateRef 'search-buton' is not a registered template"), problem);
        assertTrue(ex.getMessage().contains("POST /api/templates"), ex.getMessage());
    }

    // ---- GET_COUNT ----

    @Test
    void acceptsACountWithSomewhereToPutTheAnswer() {
        assertDoesNotThrow(() -> validator.validate(bot(
                TestSteps.step("How many rows?", ActionType.GET_COUNT,
                                LocatorDefinition.jab("table row", null, null))
                        .outputKey("rowCount").build())));
    }

    /** Reading the screen and discarding the answer is a step that does nothing. */
    @Test
    void rejectsACountWithNoOutputKey() {
        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(TestSteps.step("How many rows?", ActionType.GET_COUNT,
                        LocatorDefinition.jab("table row", null, null)).build())));

        assertTrue(ex.problems().getFirst().contains("no outputKey"), ex.problems().toString());
    }

    // ---- KEYS ----

    @Test
    void acceptsAKeysStepWithNoLocatorChain() {
        assertDoesNotThrow(() -> validator.validate(bot(
                TestSteps.step("Dismiss the dialog", ActionType.KEYS).inputValue("ESCAPE").build())));
    }

    /** Sending nothing is a silent success at run time, which is the worst kind of pass. */
    @Test
    void rejectsAKeysStepWithNothingToSend() {
        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(TestSteps.step("Send nothing", ActionType.KEYS).build())));

        assertTrue(ex.problems().getFirst().contains("no inputValue"), ex.problems().toString());
    }

    // ---- sibling selectors ----

    @Test
    void acceptsASiblingSelector() {
        assertDoesNotThrow(() -> validator.validate(bot(step("Surname field",
                LocatorDefinition.jab("text", null, null, null, null, null, "label[Surname]", null)))));
    }

    /** A '>' means the author reached for ancestorPath's chain syntax, and it would match nothing. */
    @Test
    void rejectsAChainInASiblingSelector() {
        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(step("Surname field",
                        LocatorDefinition.jab("text", null, null, null, null, null,
                                "panel > label[Surname]", null)))));

        String problem = ex.problems().getFirst();
        assertTrue(problem.contains("previousSibling"), problem);
        assertTrue(problem.contains("use ancestorPath for a chain"), problem);
    }

    @Test
    void rejectsAChainInAFollowingSiblingToo() {
        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(step("Surname field",
                        LocatorDefinition.jab("text", null, null, null, null, null, null,
                                "panel > label[Forename]")))));

        assertTrue(ex.problems().getFirst().contains("followingSibling"), ex.problems().toString());
    }

    // ---- conditional flow ----

    @Test
    void acceptsJumpsToDeclaredKeys() {
        assertDoesNotThrow(() -> validator.validate(bot(
                branching("dialog up?", "handle", "carry-on"),
                keyed("handle", "Handle"),
                keyed("carry-on", "Carry on"))));
    }

    /**
     * Caught at registration for the same reason a missing template is: at run time it surfaces
     * as a failed step on a worker, after the job was dispatched, retried and dead-lettered.
     */
    @Test
    void rejectsAJumpToAKeyNoStepDeclares() {
        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(
                        branching("dialog up?", "handel", null),
                        keyed("handle", "Handle"))));

        String problem = ex.problems().getFirst();
        assertTrue(problem.contains("onFound jumps to 'handel'"), problem);
        assertTrue(problem.contains("handle"), "the message should list the keys that do exist: " + problem);
    }

    @Test
    void rejectsBothBranchesOfAStepIndependently() {
        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(branching("dialog up?", "nowhere", "also-nowhere"))));

        assertEquals(2, ex.problems().size(), ex.problems().toString());
    }

    /** Two steps with one key makes the definition read as though the flow goes somewhere it does not. */
    @Test
    void rejectsADuplicateKey() {
        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(
                        keyed("handle", "First"),
                        keyed("handle", "Second"))));

        String problem = ex.problems().getFirst();
        assertTrue(problem.contains("reuses the key 'handle'"), problem);
    }

    @Test
    void acceptsStepsWithNoKeysAtAll() {
        assertDoesNotThrow(() -> validator.validate(bot(
                step("First", LocatorDefinition.jab("push button", "One", null)),
                step("Second", LocatorDefinition.jab("push button", "Two", null)))));
    }

    // ---- bounded loops ----

    @Test
    void acceptsALimitWithSomewhereToGoWhenItRunsOut() {
        assertDoesNotThrow(() -> validator.validate(bot(
                limited("Page down", 5, "give-up"),
                keyed("give-up", "Give up"))));
    }

    @Test
    void acceptsALimitWithNoOnLimitBecauseRunningOutMayJustFailTheRun() {
        assertDoesNotThrow(() -> validator.validate(bot(limited("Page down", 5, null))));
    }

    /** Both read as working configuration and do nothing, which is the worst kind of mistake. */
    @Test
    void rejectsALimitBelowOne() {
        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(limited("Page down", 0, null))));

        assertTrue(ex.problems().getFirst().contains("maxExecutions 0"), ex.problems().toString());
    }

    @Test
    void rejectsAnOnLimitWithNoLimitToReach() {
        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(
                        limited("Page down", null, "give-up"),
                        keyed("give-up", "Give up"))));

        assertTrue(ex.problems().getFirst().contains("no maxExecutions"), ex.problems().toString());
    }

    @Test
    void rejectsAnOnLimitJumpToAKeyNoStepDeclares() {
        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(limited("Page down", 3, "give-upp"),
                        keyed("give-up", "Give up"))));

        assertTrue(ex.problems().getFirst().contains("onLimit jumps to 'give-upp'"),
                ex.problems().toString());
    }

    @Test
    void rejectsAnUnknownAnchorTemplateRef() {
        when(templates.findByName(anyString())).thenReturn(Optional.empty());

        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(step("Type NHS number",
                        LocatorDefinition.imageAnchored(null, 0.94, "label-nhs-number", 130, 8)))));

        assertTrue(ex.problems().getFirst().contains("anchorTemplateRef 'label-nhs-number'"),
                ex.problems().getFirst());
    }

    @Test
    void reportsEveryUnresolvedReferenceAtOnce() {
        when(templates.findByName(anyString())).thenReturn(Optional.empty());

        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(
                        step("A", LocatorDefinition.image("missing-a", 0.9)),
                        step("B", LocatorDefinition.imageAnchored("missing-b", 0.9, "missing-c", 0, 20)))));

        // Three refs, three problems — one round trip to fix them all rather than three.
        assertEquals(3, ex.problems().size(), String.join(" | ", ex.problems()));
    }

    @Test
    void rejectsATemplateRegisteredWithoutImageData() {
        when(templates.findByName("blank")).thenReturn(Optional.of(template("blank", new byte[0])));

        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(step("Click", LocatorDefinition.image("blank", 0.9)))));

        assertTrue(ex.problems().getFirst().contains("registered but holds no image data"),
                ex.problems().getFirst());
    }

    @Test
    void rejectsAnImageLocatorThatNamesNoTemplateAtAll() {
        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(step("Click",
                        LocatorDefinition.image(null, 0.9)))));

        assertTrue(ex.problems().getFirst().contains("can never resolve"), ex.problems().getFirst());
    }

    @Test
    void ignoresJabLocators() {
        assertDoesNotThrow(() -> validator.validate(bot(step("Click Patient",
                LocatorDefinition.jab("push button", "Patient", NameMatch.EXACT)))));

        verify(templates, times(0)).findByName(anyString());
    }

    @Test
    void looksUpEachDistinctReferenceOnce() {
        when(templates.findByName("shared")).thenReturn(Optional.of(withImage("shared")));

        validator.validate(bot(
                step("A", LocatorDefinition.image("shared", 0.9)),
                step("B", LocatorDefinition.image("shared", 0.9)),
                step("C", LocatorDefinition.image("shared", 0.9))));

        verify(templates, times(1)).findByName("shared");
    }
}
