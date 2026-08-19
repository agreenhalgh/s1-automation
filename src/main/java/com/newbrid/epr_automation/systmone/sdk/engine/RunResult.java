package com.newbrid.epr_automation.systmone.sdk.engine;

import com.newbrid.epr_automation.systmone.common.dto.ExceptionInfo;
import com.newbrid.epr_automation.systmone.common.dto.StepReport;
import com.newbrid.epr_automation.systmone.common.model.RunStatus;

import java.util.List;
import java.util.Map;

/** Aggregate outcome of a whole run, returned by {@link BotEngine#run}. */
public record RunResult(
        RunStatus status,
        ExceptionInfo error,
        Map<String, String> outputs,
        List<StepReport> steps
) {}
