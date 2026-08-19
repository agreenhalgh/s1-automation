package com.newbrid.epr_automation.systmone.automation.window;

import com.newbrid.epr_automation.systmone.sdk.engine.WindowActivationException;
import com.newbrid.epr_automation.systmone.sdk.engine.WindowControl;

import java.util.List;

/**
 * {@link WindowControl} for hosts with no Win32 window manager (non-Windows dev boxes,
 * CI). Reports itself unavailable and refuses to activate, so an ACTIVATE step fails
 * loudly rather than appearing to succeed while the application stays in the background.
 */
public class NoopWindowControl implements WindowControl {

    private final String reason;

    public NoopWindowControl(String reason) {
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String activate(String titleContains) {
        String target = titleContains == null || titleContains.isBlank()
                ? "the application window" : "'" + titleContains + "'";
        throw new WindowActivationException("Cannot activate " + target + ": " + reason);
    }

    @Override
    public List<String> titlesMatching(String titleContains) {
        return List.of();
    }

    @Override
    public boolean isForeground(String titleContains) {
        return false;
    }
}
