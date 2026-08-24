package com.newbrid.epr_automation.gateway.route;

import java.util.List;

/**
 * A submission the gateway will not act on: no credential, an unreadable body, or a value that
 * is well-formed but wrong. Carries every problem found rather than the first, so a caller with
 * three bad fields hears about three.
 *
 * <p>Separate from Camel's own {@code BeanValidationException} because some rejections are not
 * constraint violations — a missing token, or an NHS number whose check digit does not compute —
 * and a caller should not have to care which mechanism caught what.
 */
public class SubmissionRejectedException extends Exception {

    private final int httpStatus;
    private final List<String> problems;

    public SubmissionRejectedException(int httpStatus, String message, List<String> problems) {
        super(message);
        this.httpStatus = httpStatus;
        this.problems = List.copyOf(problems);
    }

    public SubmissionRejectedException(int httpStatus, String message) {
        this(httpStatus, message, List.of());
    }

    public int httpStatus() {
        return httpStatus;
    }

    public List<String> problems() {
        return problems;
    }
}
