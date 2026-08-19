package com.newbrid.epr_automation.systmone.controlplane.service;

import com.newbrid.epr_automation.systmone.common.dto.BotDefinition;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.dto.StepDefinition;
import com.newbrid.epr_automation.systmone.common.model.LocatorType;
import com.newbrid.epr_automation.systmone.controlplane.domain.TemplateEntity;
import com.newbrid.epr_automation.systmone.controlplane.repo.TemplateRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks a bot definition against the registries before it is stored (FR-CP-08).
 *
 * <p>Its main job is template references. An image locator naming a template that was never
 * uploaded is only discovered at run time otherwise, where it surfaces as a failed step on a
 * worker — after the run was dispatched, retried and dead-lettered. Worse, a missing
 * <em>anchored</em> {@code templateRef} used to be silent: the region fell back to 1x1 and the
 * click landed at the raw offset. Rejecting the definition at registration turns both into an
 * error the author sees immediately, naming every unresolved reference at once.</p>
 */
@Component
@Profile("controlplane")
public class BotDefinitionValidator {

    private final TemplateRepository templates;

    public BotDefinitionValidator(TemplateRepository templates) {
        this.templates = templates;
    }

    /**
     * @throws RegistryValidationException if any image locator references a template that is
     *         not in the registry, holds no image data, or if an image locator names no
     *         template at all.
     */
    public void validate(BotDefinition def) {
        List<String> problems = new ArrayList<>();
        Map<String, String> checked = new HashMap<>();   // ref -> problem text, or "" if fine

        for (StepDefinition step : def.steps()) {
            List<LocatorDefinition> chain = step.locatorChain();
            for (int i = 0; i < chain.size(); i++) {
                LocatorDefinition locator = chain.get(i);
                if (locator.type() != LocatorType.IMAGE) continue;

                String where = describe(step, i);
                if (isBlank(locator.templateRef()) && isBlank(locator.anchorTemplateRef())) {
                    problems.add(where + " is an IMAGE locator with neither templateRef nor "
                            + "anchorTemplateRef, so it can never resolve");
                    continue;
                }
                checkRef(problems, checked, where, "anchorTemplateRef", locator.anchorTemplateRef());
                checkRef(problems, checked, where, "templateRef", locator.templateRef());
            }
        }

        if (!problems.isEmpty()) {
            throw new RegistryValidationException(
                    "Bot '" + def.botId() + "' references " + problems.size()
                            + " unresolved template(s); upload them with POST /api/templates first",
                    problems);
        }
    }

    private void checkRef(List<String> problems, Map<String, String> checked,
                          String where, String field, String ref) {
        if (ref == null) return;                       // absent is legal; blank is not
        String problem = checked.computeIfAbsent(ref, this::problemWith);
        if (!problem.isEmpty()) {
            problems.add(where + ": " + field + " " + problem);
        }
    }

    /** @return the problem with this reference, or an empty string if it resolves. */
    private String problemWith(String ref) {
        if (ref.isBlank()) {
            return "is blank";
        }
        TemplateEntity template = templates.findByName(ref).orElse(null);
        if (template == null) {
            return "'" + ref + "' is not a registered template";
        }
        if (template.getData() == null || template.getData().length == 0) {
            return "'" + ref + "' is registered but holds no image data";
        }
        return "";
    }

    private static String describe(StepDefinition step, int locatorIndex) {
        return "step " + step.index() + " '" + step.name() + "' locator " + locatorIndex;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
