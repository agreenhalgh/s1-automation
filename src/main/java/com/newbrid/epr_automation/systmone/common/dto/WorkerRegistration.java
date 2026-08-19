package com.newbrid.epr_automation.systmone.common.dto;

import java.util.List;

/**
 * Self-registration payload a worker POSTs on startup (FR-WRK-08), feeding
 * control-plane agent health (FR-CP-06).
 */
public record WorkerRegistration(
        String workerId,
        String host,
        String s1Version,
        int screenWidth,
        int screenHeight,
        List<String> capabilities,
        List<String> enabledAdapters
) {
    public WorkerRegistration {
        if (capabilities == null) capabilities = List.of();
        if (enabledAdapters == null) enabledAdapters = List.of();
    }
}
