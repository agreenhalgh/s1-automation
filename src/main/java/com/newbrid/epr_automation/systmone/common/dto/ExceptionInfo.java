package com.newbrid.epr_automation.systmone.common.dto;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Serialisable failure detail POSTed to the control plane (FR-WRK-03, FR-CP-02):
 * exception type, message, stack trace and an optional context string (e.g. the
 * resolved locator or step description).
 */
public record ExceptionInfo(
        String type,
        String message,
        String stackTrace,
        String context
) {
    public static ExceptionInfo from(Throwable t, String context) {
        if (t == null) return null;
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String msg = t.getMessage();
        // Cap the stack trace so a pathological trace cannot bloat the payload.
        String trace = sw.toString();
        if (trace.length() > 16_000) {
            trace = trace.substring(0, 16_000) + "\n... [truncated]";
        }
        return new ExceptionInfo(t.getClass().getName(), msg, trace, context);
    }
}
