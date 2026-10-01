package com.newbrid.epr_automation.pdsreview.intake;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The route the gateway POSTs unmatched presentations to (FR-INT-02a).
 *
 * <p><b>This is the only way into the application that is not a CIS2 reviewer session</b>, and it
 * is deliberately the narrowest thing that works: one POST, no GET, no listing, no lookup. A
 * machine credential must not become a way to read the queue without being a named clinician
 * (NFR-SEC-01a). Authentication is in {@code IntakeTokenFilter}.
 *
 * <p><b>The body is the presentation itself</b>, exactly as OPTO Health sent it, and exactly what
 * gets stored (FR-INT-04). Wrapping it in an envelope of our own would mean this application
 * defining a schema for somebody else's payload, and the post-back could no longer hand it onward
 * unaltered.
 *
 * <p>The three answers are distinguishable by status code, because the gateway's retry logic
 * depends on telling them apart (FR-INT-02b):
 * <ul>
 *   <li><b>201</b> admitted, and committed — safe to stop retrying</li>
 *   <li><b>200</b> already held — a redelivery; nothing changed, stop retrying</li>
 *   <li><b>422</b> not admissible — matched, or no identity to key it on; retrying will not
 *       help, and this is a case for a human at the gateway end</li>
 * </ul>
 * A 5xx or a connection failure means neither of those, and the gateway should try again.
 */
@RestController
public class IntakeController {

    private final PresentationIntakeService intake;

    public IntakeController(PresentationIntakeService intake) {
        this.intake = intake;
    }

    /**
     * @param correlationId the gateway's id for this presentation, so this application's trail
     *                      joins up with the gateway's (FR-AUD-04)
     */
    @PostMapping(path = "${systmone.pds-review.intake.path:/internal/v1/presentations}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IntakeResponse> receive(
            @RequestBody JsonNode presentation,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        PresentationIntakeService.Result result = intake.admit(presentation, correlationId);

        return switch (result.outcome()) {
            case ADMITTED -> ResponseEntity.status(HttpStatus.CREATED)
                    .body(IntakeResponse.admitted(result.itemKey(), correlationId));
            case ALREADY_HELD -> ResponseEntity.ok(
                    IntakeResponse.alreadyHeld(result.itemKey(), correlationId));
            case REJECTED -> ResponseEntity.unprocessableEntity()
                    .body(IntakeResponse.rejected(result.itemKey(), correlationId, result.reason()));
        };
    }

    /**
     * @param outcome     the same three values the service reports, echoed so the gateway can
     *                    audit what it was told rather than inferring it from a status code
     * @param retryUseful whether trying again could change the answer. Always false here: every
     *                    outcome this endpoint returns is final. A retry is warranted only when
     *                    the gateway got no answer at all
     */
    public record IntakeResponse(String outcome, String itemKey, String correlationId,
                                 boolean retryUseful, String reason) {

        static IntakeResponse admitted(String itemKey, String correlationId) {
            return new IntakeResponse("ADMITTED", itemKey, correlationId, false, null);
        }

        static IntakeResponse alreadyHeld(String itemKey, String correlationId) {
            return new IntakeResponse("ALREADY_HELD", itemKey, correlationId, false, null);
        }

        static IntakeResponse rejected(String itemKey, String correlationId, String reason) {
            return new IntakeResponse("REJECTED", itemKey, correlationId, false, reason);
        }
    }
}
