package com.newbrid.epr_automation.gateway.route;

import com.newbrid.epr_automation.gateway.GatewayProperties;
import com.newbrid.epr_automation.gateway.api.RunSubmission;
import com.newbrid.epr_automation.gateway.controlplane.ControlPlaneWire.DispatchRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The transform step: the gateway's external contract into the control plane's dispatch request.
 *
 * <p>This is the seam that lets the two contracts move independently. {@code bot} becomes
 * {@code botId}, {@code pool} becomes {@code capabilityTag}, and the caller's structured subject
 * and parameters are flattened into the single {@code Map<String, String>} that a bot
 * definition's placeholders resolve against.
 *
 * <p>Patient identifiers <b>are</b> passed onward — a bot that looks up a record needs the NHS
 * number to do it. What does not happen is the same data being copied into the gateway's audit
 * trail; that gets a keyed reference instead. Passing PID to the system that must act on it and
 * keeping it out of the system that only needs to remember the request are different questions,
 * and conflating them would either break the bots or fill an audit table with patient data.
 */
@Component
public class SubmissionTransformer {

    /**
     * Prefix for the keys the subject is flattened into, so a bot definition refers to
     * {@code ${subject.nhsNumber}} and a caller's own parameter can never silently occupy the
     * same name.
     */
    public static final String SUBJECT_NHS_NUMBER = "subject.nhsNumber";
    public static final String SUBJECT_LOCAL_ID = "subject.localId";

    private final GatewayProperties properties;

    public SubmissionTransformer(GatewayProperties properties) {
        this.properties = properties;
    }

    public DispatchRequest toDispatchRequest(RunSubmission submission) {
        Map<String, String> input = new LinkedHashMap<>(submission.parametersOrEmpty());

        RunSubmission.Subject subject = submission.subject();
        if (subject != null) {
            putIfPresent(input, SUBJECT_NHS_NUMBER, subject.nhsNumber());
            putIfPresent(input, SUBJECT_LOCAL_ID, subject.localId());
        }

        return new DispatchRequest(submission.bot(), input, blankToNull(submission.pool()));
    }

    /**
     * How long to wait for this submission: what the caller asked for, clamped to the configured
     * ceiling, or the default if they did not ask. Clamped rather than rejected, because a caller
     * asking to wait longer than policy allows has made a reasonable request that policy happens
     * to override — and the response reports the wait actually used.
     */
    public long waitMillisFor(RunSubmission submission) {
        int seconds = submission.waitSeconds() != null
                ? Math.min(submission.waitSeconds(), properties.getRun().getMaxWaitSeconds())
                : properties.getRun().getDefaultWaitSeconds();
        return seconds * 1000L;
    }

    /**
     * The NHS number check digit (modulus 11). Shape is enforced by the {@code @Pattern} on the
     * request record; this is the arithmetic that separates ten plausible digits from a real
     * identifier, and it belongs at the gateway rather than in a bot: a mistyped number sends a
     * worker hunting for a patient who cannot exist, and the failure surfaces minutes later as
     * "no locator resolved" rather than as "that is not an NHS number".
     *
     * <p>Weights run 10 down to 2 across the first nine digits; the remainder of the total
     * modulo 11 subtracted from 11 is the tenth. A remainder of 1 yields 10, which no single
     * digit can be, so those numbers are simply never issued.
     */
    public static boolean hasValidNhsCheckDigit(String nhsNumber) {
        if (nhsNumber == null || nhsNumber.length() != 10 || !nhsNumber.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int total = 0;
        for (int i = 0; i < 9; i++) {
            total += (nhsNumber.charAt(i) - '0') * (10 - i);
        }
        int remainder = total % 11;
        int expected = 11 - remainder;
        if (expected == 11) {
            expected = 0;
        }
        if (expected == 10) {
            return false;
        }
        return expected == (nhsNumber.charAt(9) - '0');
    }

    private static void putIfPresent(Map<String, String> input, String key, String value) {
        if (value != null && !value.isBlank()) {
            input.put(key, value.trim());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
