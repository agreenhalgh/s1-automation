package com.newbrid.epr_automation.systmone.sdk.engine;

/**
 * A window could not be brought to the foreground: none matched, the title was
 * ambiguous, or the shell refused the request. The engine records this as a step
 * failure rather than letting subsequent keystrokes land in an unknown window.
 */
public class WindowActivationException extends RuntimeException {

    public WindowActivationException(String message) {
        super(message);
    }
}
