package com.newbrid.epr_automation.systmone.controlplane.service;

import java.util.List;

/**
 * A submission was rejected because it cannot work as written — as opposed to referring to
 * something that does not exist (404) or conflicting with current state (409). Carries one
 * entry per problem so the caller can fix them all in one pass rather than one per attempt.
 */
public class RegistryValidationException extends RuntimeException {

    private final List<String> problems;

    public RegistryValidationException(String message, List<String> problems) {
        super(message + (problems.isEmpty() ? "" : ": " + String.join("; ", problems)));
        this.problems = List.copyOf(problems);
    }

    public RegistryValidationException(String message) {
        this(message, List.of());
    }

    public List<String> problems() {
        return problems;
    }
}
