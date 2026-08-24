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

        List<StepDefinition> steps = def.steps();
        for (int position = 0; position < steps.size(); position++) {
            StepDefinition step = steps.get(position);
            List<LocatorDefinition> chain = step.locatorChain();
            for (int i = 0; i < chain.size(); i++) {
                LocatorDefinition locator = chain.get(i);
                if (locator.type() != LocatorType.IMAGE) continue;

                checkLocator(problems, checked, describe(position, step, i), locator);
            }
            for (int i = 0; i < chain.size(); i++) {
                checkSiblingSelectors(problems, describe(position, step, i), chain.get(i));
                checkCellAddress(problems, describe(position, step, i), chain.get(i));
            }
            // A table size with nowhere to put the answer reads the screen and throws it away, and
            // its two outputs are derived from the key, so a blank one makes ".rows" and ".columns".
            if (step.action() == com.newbrid.epr_automation.systmone.common.model.ActionType.GET_TABLE_SIZE
                    && (step.outputKey() == null || step.outputKey().isBlank())) {
                problems.add("step " + position + " '" + step.name() + "' is a GET_TABLE_SIZE step "
                        + "with no outputKey, so there is nothing to name its .rows and .columns");
            }
            // A count with nowhere to put the answer reads the screen and throws it away.
            if (step.action() == com.newbrid.epr_automation.systmone.common.model.ActionType.GET_COUNT
                    && (step.outputKey() == null || step.outputKey().isBlank())) {
                problems.add("step " + position + " '" + step.name() + "' is a GET_COUNT step with "
                        + "no outputKey, so the count it reads would go nowhere");
            }
            // A KEYS step with nothing to send is a silent no-op at run time: the keystrokes are
            // simply not sent and the step reports success.
            if (step.action() == com.newbrid.epr_automation.systmone.common.model.ActionType.KEYS
                    && (step.inputValue() == null || step.inputValue().isBlank())) {
                problems.add("step " + position + " '" + step.name() + "' is a KEYS step with no "
                        + "inputValue, so it would send nothing");
            }
        }
        checkFlow(problems, def);

        if (!problems.isEmpty()) {
            throw new RegistryValidationException(
                    "Bot '" + def.botId() + "' has " + problems.size() + " problem(s): unresolved "
                            + "template references need uploading with POST /api/templates first, and "
                            + "flow problems need fixing in the definition",
                    problems);
        }
    }

    /**
     * The same check for a locator authored on its own rather than inside a bot — what the
     * capture tool saves (FR-REG-01, FR-SR-07). Catching a dangling reference here means the
     * author finds out while looking at the screenshot, not when a run fails days later.
     *
     * @throws RegistryValidationException if the locator's template references do not resolve
     */
    public void validate(LocatorDefinition locator, String locatorName) {
        if (locator.type() != LocatorType.IMAGE) return;
        List<String> problems = new ArrayList<>();
        checkLocator(problems, new HashMap<>(), "locator '" + locatorName + "'", locator);
        if (!problems.isEmpty()) {
            throw new RegistryValidationException(
                    "Locator '" + locatorName + "' references " + problems.size()
                            + " unresolved template(s); upload them with POST /api/templates first",
                    problems);
        }
    }

    private void checkLocator(List<String> problems, Map<String, String> checked, String where,
                              LocatorDefinition locator) {
        if (isBlank(locator.templateRef()) && isBlank(locator.anchorTemplateRef())) {
            problems.add(where + " is an IMAGE locator with neither templateRef nor "
                    + "anchorTemplateRef, so it can never resolve");
            return;
        }
        checkRef(problems, checked, where, "anchorTemplateRef", locator.anchorTemplateRef());
        checkRef(problems, checked, where, "templateRef", locator.templateRef());
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

    /**
     * A sibling selector names <em>one</em> element, not a path. A {@code >} in it means the
     * author expected {@code ancestorPath}'s chain syntax, and it would silently match nothing:
     * no single element has a role containing an angle bracket.
     */
    /**
     * A row or column must be a number or a {@code ${placeholder}}. Anything else is a typo that
     * would otherwise reach a worker and fail there, one dispatched job later.
     */
    private static void checkCellAddress(List<String> problems, String where,
                                         LocatorDefinition locator) {
        checkCellNumber(problems, where, "row", locator.row());
        checkCellNumber(problems, where, "column", locator.column());
    }

    private static void checkCellNumber(List<String> problems, String where, String field,
                                        String value) {
        if (value == null || value.contains("${")) return;
        try {
            Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            problems.add(where + ": " + field + " '" + value + "' is neither a number nor a "
                    + "${placeholder}");
        }
    }

    private static void checkSiblingSelectors(List<String> problems, String where,
                                              LocatorDefinition locator) {
        checkSiblingSelector(problems, where, "previousSibling", locator.previousSibling());
        checkSiblingSelector(problems, where, "followingSibling", locator.followingSibling());
        checkSiblingSelector(problems, where, "parent", locator.parent());
    }

    private static void checkSiblingSelector(List<String> problems, String where, String field,
                                             String selector) {
        if (selector != null && selector.contains(">")) {
            problems.add(where + ": " + field + " '" + selector + "' names one element, so it takes "
                    + "a single role[name] and not a '>' chain — use ancestorPath for a chain");
        }
    }

    /**
     * Step keys and the jumps between them (FR-ENG-01).
     *
     * <p>Checked here for the same reason template references are: a jump to a key nothing
     * declares is a typo, and finding out at run time means a dispatched job, a failed step on a
     * worker and a dead-lettered run before anyone learns of it. Duplicated keys are worse than
     * a typo — the definition reads as though the flow goes one place and it goes another.</p>
     */
    private static void checkFlow(List<String> problems, BotDefinition def) {
        List<StepDefinition> steps = def.steps();
        Map<String, Integer> firstSeen = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            String key = steps.get(i).key();
            if (key == null) continue;
            Integer earlier = firstSeen.putIfAbsent(key, i);
            if (earlier != null) {
                problems.add("step " + i + " '" + steps.get(i).name() + "' reuses the key '" + key
                        + "', already declared by step " + earlier + " — a jump to it would be a coin toss");
            }
        }
        for (int i = 0; i < steps.size(); i++) {
            StepDefinition step = steps.get(i);
            checkTarget(problems, firstSeen, i, step, "onFound", step.onFound());
            checkTarget(problems, firstSeen, i, step, "onMissing", step.onMissing());
            checkTarget(problems, firstSeen, i, step, "onLimit", step.onLimit());
            checkLimit(problems, i, step);
        }
    }

    /**
     * A limit has to be a number of executions, and a jump for exhausting it has to have a limit
     * to exhaust. Both mistakes read as working configuration and do nothing at run time.
     */
    private static void checkLimit(List<String> problems, int position, StepDefinition step) {
        Integer max = step.maxExecutions();
        if (max != null && max < 1) {
            problems.add("step " + position + " '" + step.name() + "' has maxExecutions " + max
                    + "; it must be at least 1, or absent for no limit");
        }
        if (step.onLimit() != null && max == null) {
            problems.add("step " + position + " '" + step.name() + "' declares onLimit '"
                    + step.onLimit() + "' but no maxExecutions, so there is no limit to reach");
        }
    }

    private static void checkTarget(List<String> problems, Map<String, Integer> keys, int position,
                                    StepDefinition step, String field, String target) {
        if (target == null) return;
        if (keys.containsKey(target)) return;
        String known = keys.isEmpty() ? "no step declares a key"
                : "known keys: " + String.join(", ", keys.keySet());
        problems.add("step " + position + " '" + step.name() + "' " + field + " jumps to '"
                + target + "', which no step declares as its key (" + known + ")");
    }

    /** Position, not a declared number: it is what the run reports and what the author counts. */
    private static String describe(int position, StepDefinition step, int locatorIndex) {
        return "step " + position + " '" + step.name() + "' locator " + locatorIndex;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
