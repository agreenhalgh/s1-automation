package com.newbrid.epr_automation.systmone.sdk.engine;

import com.newbrid.epr_automation.systmone.common.dto.ExceptionInfo;
import com.newbrid.epr_automation.systmone.common.dto.StepReport;
import com.newbrid.epr_automation.systmone.common.model.RunStatus;

import java.util.Map;

/**
 * Callback surface the engine invokes as a run progresses. The worker implements this
 * to POST results to the control plane (FR-WRK-03); tests use it to assert behaviour.
 */
public interface RunListener {

    default void onRunStarted(String runId) {}

    /** Fired as each step completes (success or failure). */
    void onStepComplete(StepReport report);

    /** Fired once when the run reaches a terminal state. */
    void onRunComplete(RunStatus status, ExceptionInfo error, Map<String, String> outputs);

    /** Optional progress ping the engine emits before each step (FR-WRK-06). */
    default void onProgress(int stepIndex) {}

    RunListener NOOP = new RunListener() {
        @Override public void onStepComplete(StepReport report) {}
        @Override public void onRunComplete(RunStatus s, ExceptionInfo e, Map<String, String> o) {}
    };
}
