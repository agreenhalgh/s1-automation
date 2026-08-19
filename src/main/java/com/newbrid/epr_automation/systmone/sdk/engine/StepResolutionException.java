package com.newbrid.epr_automation.systmone.sdk.engine;

/** Thrown when no locator in a step's chain resolves to a confident element. */
public class StepResolutionException extends RuntimeException {
    public StepResolutionException(String message) {
        super(message);
    }
}
