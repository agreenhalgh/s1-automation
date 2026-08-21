package com.newbrid.epr_automation.systmone.sdk.locator;

import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.common.model.ActionType;
import com.newbrid.epr_automation.systmone.common.model.LocatorType;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Optional;

/**
 * Strategy abstraction over the two automation mechanisms — JAB and screen
 * recognition — so a bot step is agnostic to which one resolves it (NFR-MNT-01).
 *
 * <p><b>Fail-safe contract (NFR-REL-04):</b> {@link #resolve} must return
 * {@link Optional#empty()} rather than a best guess whenever the element cannot be
 * located with confidence — including ambiguous matches. The engine then falls
 * through to the next locator or fails the step; it never acts on an uncertain
 * target. This is critical in a clinical system.</p>
 */
public interface AutomationBackend {

    /** The locator type this backend handles. */
    LocatorType handles();

    /**
     * Whether this backend can actually operate in the current environment (e.g. the
     * Windows Access Bridge DLL loaded; a non-headless display is present). When
     * false the engine skips this backend's locators and uses the fallback chain.
     */
    boolean isAvailable();

    /**
     * Attempt to locate an element, polling until found or the timeout elapses.
     *
     * @return the resolved element, or empty if not found / not confident / ambiguous
     */
    Optional<ElementHandle> resolve(LocatorDefinition locator, Duration timeout);

    /**
     * Perform an action on a resolved element.
     *
     * @return the value read for GET_TEXT / READ_STATE; otherwise {@code null}
     * @throws UnsupportedOperationException if this backend cannot perform the action
     */
    String perform(ActionType action, ElementHandle element, ActionParams params);

    /** Capture a diagnostic screenshot (NFR-OBS-02); may return {@code null} if unavailable. */
    BufferedImage captureScreenshot();

    /**
     * How many elements this locator matches.
     *
     * <p>Deliberately not expressed through {@link #resolve}: that returns at most one element and
     * treats several as ambiguous, which is the opposite of what a count wants.</p>
     *
     * @throws UnsupportedOperationException by default. An image locator has no meaningful count —
     *         the matcher reports a best match and a runner-up, not a set — so a backend says so
     *         rather than inventing a number.
     */
    default int count(LocatorDefinition locator, Duration timeout) {
        throw new UnsupportedOperationException(handles()
                + " locators cannot be counted, only matched. Count JAB elements instead.");
    }
}
