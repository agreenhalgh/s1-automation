package com.newbrid.epr_automation.systmone.controlplane.service;

import com.newbrid.epr_automation.systmone.common.dto.BotDefinition;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.dto.StepDefinition;
import com.newbrid.epr_automation.systmone.common.model.ActionType;
import com.newbrid.epr_automation.systmone.common.model.NameMatch;
import com.newbrid.epr_automation.systmone.controlplane.domain.TemplateEntity;
import com.newbrid.epr_automation.systmone.controlplane.repo.TemplateRepository;
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

    private static StepDefinition step(int index, String name, LocatorDefinition... chain) {
        return new StepDefinition(index, name, ActionType.CLICK, List.of(chain),
                null, null, 5000, null, false, null, null);
    }

    @Test
    void acceptsADefinitionWhoseTemplatesAllExist() {
        when(templates.findByName("search-button")).thenReturn(Optional.of(withImage("search-button")));
        when(templates.findByName("label-nhs")).thenReturn(Optional.of(withImage("label-nhs")));

        assertDoesNotThrow(() -> validator.validate(bot(
                step(0, "Search", LocatorDefinition.image("search-button", 0.92)),
                step(1, "Type", LocatorDefinition.imageAnchored(null, 0.94, "label-nhs", 130, 8)))));
    }

    @Test
    void rejectsAnUnknownTemplateRefNamingStepAndField() {
        when(templates.findByName(anyString())).thenReturn(Optional.empty());

        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(step(3, "Click Search",
                        LocatorDefinition.image("search-buton", 0.92)))));

        assertEquals(1, ex.problems().size());
        String problem = ex.problems().getFirst();
        assertTrue(problem.contains("step 3 'Click Search' locator 0"), problem);
        assertTrue(problem.contains("templateRef 'search-buton' is not a registered template"), problem);
        assertTrue(ex.getMessage().contains("POST /api/templates"), ex.getMessage());
    }

    @Test
    void rejectsAnUnknownAnchorTemplateRef() {
        when(templates.findByName(anyString())).thenReturn(Optional.empty());

        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(step(0, "Type NHS number",
                        LocatorDefinition.imageAnchored(null, 0.94, "label-nhs-number", 130, 8)))));

        assertTrue(ex.problems().getFirst().contains("anchorTemplateRef 'label-nhs-number'"),
                ex.problems().getFirst());
    }

    @Test
    void reportsEveryUnresolvedReferenceAtOnce() {
        when(templates.findByName(anyString())).thenReturn(Optional.empty());

        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(
                        step(0, "A", LocatorDefinition.image("missing-a", 0.9)),
                        step(1, "B", LocatorDefinition.imageAnchored("missing-b", 0.9, "missing-c", 0, 20)))));

        // Three refs, three problems — one round trip to fix them all rather than three.
        assertEquals(3, ex.problems().size(), String.join(" | ", ex.problems()));
    }

    @Test
    void rejectsATemplateRegisteredWithoutImageData() {
        when(templates.findByName("blank")).thenReturn(Optional.of(template("blank", new byte[0])));

        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(step(0, "Click", LocatorDefinition.image("blank", 0.9)))));

        assertTrue(ex.problems().getFirst().contains("registered but holds no image data"),
                ex.problems().getFirst());
    }

    @Test
    void rejectsAnImageLocatorThatNamesNoTemplateAtAll() {
        RegistryValidationException ex = assertThrows(RegistryValidationException.class,
                () -> validator.validate(bot(step(0, "Click",
                        LocatorDefinition.image(null, 0.9)))));

        assertTrue(ex.problems().getFirst().contains("can never resolve"), ex.problems().getFirst());
    }

    @Test
    void ignoresJabLocators() {
        assertDoesNotThrow(() -> validator.validate(bot(step(0, "Click Patient",
                LocatorDefinition.jab("push button", "Patient", NameMatch.EXACT)))));

        verify(templates, times(0)).findByName(anyString());
    }

    @Test
    void looksUpEachDistinctReferenceOnce() {
        when(templates.findByName("shared")).thenReturn(Optional.of(withImage("shared")));

        validator.validate(bot(
                step(0, "A", LocatorDefinition.image("shared", 0.9)),
                step(1, "B", LocatorDefinition.image("shared", 0.9)),
                step(2, "C", LocatorDefinition.image("shared", 0.9))));

        verify(templates, times(1)).findByName("shared");
    }
}
