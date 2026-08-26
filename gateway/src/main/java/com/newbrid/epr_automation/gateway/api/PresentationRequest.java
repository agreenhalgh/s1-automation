package com.newbrid.epr_automation.gateway.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * The body OPTO Health POSTs to {@code /api/v1/presentation} — one completed triage session for
 * one patient. Described by {@code docs/answer_digital-presentation.openapi.yaml} and its source
 * JSON Schema; sample bodies live in {@code gateway/src/test/resources/post-*.json}.
 *
 * <p>Two fields, and the envelope is the odd part: the entire clinical payload hangs off
 * {@code externalValue} with a fixed {@code summary} label beside it. That is OPTO Health's
 * shape, not one this gateway would have chosen, and it is reproduced exactly rather than
 * flattened — a sender's envelope is the one thing an integration cannot renegotiate unilaterally.
 *
 * @param summary       always the literal {@code OPTO Health}. A {@code const} in the source
 *                      schema, so a different value means the body did not come from the feed
 *                      this endpoint is for, which is worth failing on rather than ignoring
 * @param externalValue the presentation itself
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record PresentationRequest(

        @Pattern(regexp = "OPTO Health", message = "summary must be exactly 'OPTO Health'")
        String summary,

        @NotNull(message = "externalValue is required")
        @Valid
        Presentation externalValue) {}
