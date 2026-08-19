package com.newbrid.epr_automation.systmone.controlplane.web;

import com.newbrid.epr_automation.systmone.controlplane.service.RegistryValidationException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Translates common service exceptions into meaningful HTTP status codes for the REST API
 * — e.g. an unknown run/bot id becomes 404 rather than a generic 500.
 */
@RestControllerAdvice(basePackages = "com.newbrid.epr_automation.systmone.controlplane.web.api")
@Profile("controlplane")
public class ApiExceptionHandler {

    /**
     * A submission that cannot work as written — e.g. a bot whose image locators name
     * templates that are not in the registry. The {@code problems} array lists every
     * offending reference so the author can fix them in one pass.
     */
    @ExceptionHandler(RegistryValidationException.class)
    public ResponseEntity<Map<String, Object>> invalid(RegistryValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage(), "problems", ex.problems()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> notFound(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }
}
