package com.newbrid.epr_automation.systmone.sdk.engine;

/**
 * Sends keystrokes to whatever holds the keyboard focus, with no element involved.
 *
 * <p>The seam exists because a {@code KEYS} step with no locator chain has nothing to resolve and
 * nothing to act on: keystrokes go to the focused window whether or not the engine found an
 * element first. Both automation backends already discard the element they resolve for a
 * {@code KEYS} action — the chain was only ever a gate — so this is the honest home for it, the
 * same way {@link WindowControl} is the home for {@code ACTIVATE}.</p>
 */
public interface KeyboardControl {

    /** Whether keystrokes can actually be sent on this host. */
    boolean isAvailable();

    /** Send one chord, or a comma-separated sequence of them: {@code "DOWN,DOWN,ENTER"}. */
    void keys(String combo);

    /**
     * Default for an engine built without a keyboard. Fails the step rather than reporting
     * success for keystrokes that went nowhere.
     */
    KeyboardControl UNAVAILABLE = new KeyboardControl() {
        @Override public boolean isAvailable() { return false; }

        @Override public void keys(String combo) {
            throw new UnsupportedOperationException(
                    "This engine has no keyboard configured, so it cannot send '" + combo
                            + "'. A KEYS step without a locator chain needs one: on a worker that "
                            + "is java.awt.Robot, which is unavailable in a headless session.");
        }
    };
}
