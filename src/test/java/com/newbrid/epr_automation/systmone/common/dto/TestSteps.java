package com.newbrid.epr_automation.systmone.common.dto;

import com.newbrid.epr_automation.systmone.common.model.ActionType;

import java.util.List;

/**
 * Builds {@link StepDefinition}s for tests.
 *
 * <p>{@code StepDefinition} is a wide record because it is a wire format, and every test that
 * constructed it positionally had to be edited each time a field was added — three times over, for
 * changes none of those tests cared about. Naming only the fields a test is about keeps that churn
 * in this one file, and keeps each test readable about what it is actually varying.</p>
 */
public final class TestSteps {

    private String key;
    private String name = "step";
    private ActionType action = ActionType.CLICK;
    private List<LocatorDefinition> chain = List.of();
    private String inputValue;
    private String outputKey;
    private long timeoutMs = 5000;
    private RetryPolicyDef retry = RetryPolicyDef.none();
    private boolean continueOnFailure;
    private Integer clickOffsetX;
    private Integer clickOffsetY;
    private String onFound;
    private String onMissing;
    private Integer maxExecutions;
    private String onLimit;

    private TestSteps() {}

    public static TestSteps step(String name, ActionType action, LocatorDefinition... chain) {
        TestSteps s = new TestSteps();
        s.name = name;
        s.action = action;
        s.chain = List.of(chain);
        return s;
    }

    /** A step that names itself, so a conditional flow has something to jump to. */
    public static TestSteps keyed(String key, ActionType action, LocatorDefinition... chain) {
        return step(key, action, chain).key(key);
    }

    public TestSteps key(String key) { this.key = key; return this; }
    public TestSteps inputValue(String inputValue) { this.inputValue = inputValue; return this; }
    public TestSteps outputKey(String outputKey) { this.outputKey = outputKey; return this; }
    public TestSteps timeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; return this; }
    public TestSteps retry(RetryPolicyDef retry) { this.retry = retry; return this; }
    public TestSteps continueOnFailure(boolean value) { this.continueOnFailure = value; return this; }
    public TestSteps onFound(String onFound) { this.onFound = onFound; return this; }
    public TestSteps onMissing(String onMissing) { this.onMissing = onMissing; return this; }
    public TestSteps maxExecutions(Integer maxExecutions) { this.maxExecutions = maxExecutions; return this; }
    public TestSteps onLimit(String onLimit) { this.onLimit = onLimit; return this; }

    public StepDefinition build() {
        return new StepDefinition(key, name, action, chain, inputValue, outputKey, timeoutMs, retry,
                continueOnFailure, clickOffsetX, clickOffsetY, onFound, onMissing, maxExecutions, onLimit);
    }
}
