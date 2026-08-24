package com.newbrid.epr_automation.gateway.api;

import java.util.List;

/**
 * The shape of every non-2xx answer this gateway gives, so a caller has one error format to
 * handle rather than one per failure mode.
 *
 * @param correlationId always present, including on a rejection, because the matching audit
 *                      entry is keyed on it — "which request was that?" has to be answerable
 *                      from the error alone
 * @param problems      one entry per thing wrong, so a caller with three invalid fields learns
 *                      about all three from one call instead of discovering them one at a time
 */
public record ApiProblem(String error, String correlationId, String requestId,
                         String runId, List<String> problems) {

    public static ApiProblem of(String error, String correlationId, String requestId) {
        return new ApiProblem(error, correlationId, requestId, null, List.of());
    }

    public static ApiProblem of(String error, String correlationId, String requestId,
                                List<String> problems) {
        return new ApiProblem(error, correlationId, requestId, null, problems);
    }
}
