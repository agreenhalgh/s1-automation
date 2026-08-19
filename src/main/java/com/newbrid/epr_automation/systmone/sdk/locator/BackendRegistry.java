package com.newbrid.epr_automation.systmone.sdk.locator;

import com.newbrid.epr_automation.systmone.common.model.LocatorType;

import java.util.EnumMap;
import java.util.Map;

/** Maps a {@link LocatorType} to the backend that handles it. */
public class BackendRegistry {

    private final Map<LocatorType, AutomationBackend> backends = new EnumMap<>(LocatorType.class);

    public BackendRegistry register(AutomationBackend backend) {
        backends.put(backend.handles(), backend);
        return this;
    }

    /** The backend for a locator type, or {@code null} if none is registered. */
    public AutomationBackend get(LocatorType type) {
        return backends.get(type);
    }

    public boolean isAvailable(LocatorType type) {
        AutomationBackend b = backends.get(type);
        return b != null && b.isAvailable();
    }
}
