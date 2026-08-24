package com.newbrid.epr_automation.systmone.sdk.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-run state: the immutable input record (FR-ENG-04) plus accumulating outputs
 * (values read by GET_TEXT / READ_STATE steps). Supports {@code ${var}} placeholder
 * substitution in step input values, resolving against outputs first, then input.
 */
public class ExecutionContext {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private final Map<String, String> input;
    private final Map<String, String> outputs = new LinkedHashMap<>();
    private final Map<String, String> implicit = new LinkedHashMap<>();

    public ExecutionContext(Map<String, String> input) {
        this.input = input == null ? Map.of() : Map.copyOf(input);
    }

    public void putOutput(String key, String value) {
        if (key != null) outputs.put(key, value);
    }

    public Map<String, String> outputs() {
        return new LinkedHashMap<>(outputs);
    }

    /**
     * Set a variable the engine provides rather than the bot: the loop counters, which change
     * every time a step runs.
     *
     * <p>Held apart from the outputs deliberately. They are not results of the run and do not
     * belong in what it reports, and keeping them separate means a bot's own output can shadow one
     * rather than being silently overwritten by it.</p>
     */
    public void putImplicit(String key, String value) {
        if (key != null) implicit.put(key, value);
    }

    /**
     * Replace {@code ${key}} placeholders; unknown keys are left as-is.
     *
     * <p>Resolved against the outputs first, then the run input, then the engine's own variables —
     * so a bot that names an output after a loop counter gets its own value, not the engine's.</p>
     */
    public String resolve(String template) {
        if (template == null || template.indexOf('$') < 0) return template;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = outputs.containsKey(key) ? outputs.get(key)
                    : input.containsKey(key) ? input.get(key)
                    : implicit.get(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
