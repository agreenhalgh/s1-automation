package com.newbrid.epr_automation.systmone.authoring.web;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Turns the tool's failures into messages the page can show next to the screenshot. Authoring is
 * an interactive loop — "the anchor is not on this screen" is information, not a stack trace —
 * so every one of these carries the sentence the author needs.
 */
@RestControllerAdvice(basePackageClasses = AuthoringApiController.class)
@Profile("authoring")
public class AuthoringExceptionHandler {

    /** A selection or reference that cannot work, and a discarded or evicted capture. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    /** A worker that cannot capture, or an anchor that is not on the screen. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unprocessable(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", ex.getMessage()));
    }

    /** The control plane refusing or unreachable — usually the token or the URL. */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, String>> registryUnavailable(RestClientException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "The control-plane registry rejected or refused the request: "
                        + ex.getMessage()));
    }
}
