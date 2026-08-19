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

    public ExecutionContext(Map<String, String> input) {
        this.input = input == null ? Map.of() : Map.copyOf(input);
    }

    public void putOutput(String key, String value) {
        if (key != null) outputs.put(key, value);
    }

    public Map<String, String> outputs() {
        return new LinkedHashMap<>(outputs);
    }

    /** Replace {@code ${key}} placeholders; unknown keys are left as-is. */
    public String resolve(String template) {
        if (template == null || template.indexOf('$') < 0) return template;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = outputs.containsKey(key) ? outputs.get(key) : input.get(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
