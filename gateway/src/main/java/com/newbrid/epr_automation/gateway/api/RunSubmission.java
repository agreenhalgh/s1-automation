package com.newbrid.epr_automation.gateway.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * The gateway's <b>external</b> contract — deliberately not the control plane's
 * {@code DispatchRequest}. A calling system should not have to know that a bot is addressed by
 * {@code botId}, that routing is called a {@code capabilityTag}, or that inputs are a flat
 * string map; those are internal names that must stay free to change. Mapping between the two
 * is what the transform step of this gateway exists to do.
 *
 * <p>Constraints live here as annotations rather than as checks in a route, so the contract and
 * its rules are one artefact. The route runs them through {@code bean-validator:} and turns a
 * violation into a 400 listing every field at fault, so a caller fixes them in one pass rather
 * than one per round trip.
 *
 * <p>Unknown properties are rejected, unlike on the responses this gateway reads back. An
 * unrecognised field in an inbound clinical request is far more likely to be a caller sending
 * something important to the wrong name than it is to be harmless padding — silently dropping
 * it would mean running a bot against a patient with a parameter the caller believes they set.
 *
 * @param requestId  the caller's own id for this submission, echoed back and recorded in the
 *                   audit trail so their logs and ours can be reconciled. Optional
 * @param bot        which bot to run, as registered in the control plane
 * @param subject    who the run is about. Optional, because not every bot is patient-scoped
 * @param parameters bot inputs, passed through to the run. Values are strings because that is
 *                   what a bot definition's placeholders resolve against
 * @param pool       optional routing tag, matched against a worker's capability
 * @param waitSeconds how long the caller is prepared to hold the connection open waiting for a
 *                   definitive outcome. Bounded at both ends: too small and no run could ever
 *                   finish in time, too large and a caller could pin a request thread for an
 *                   hour. Absent means the server default
 * @param user       who is submitting. Required when a document is attached, because the stored
 *                   file is named after them
 * @param pdfBase64  an optional PDF to store alongside the run, base64-encoded. Stored before
 *                   the run is dispatched and its path passed to the run, so a bot that files a
 *                   document has something to file
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RunSubmission(

        @Size(max = 128, message = "requestId must be at most 128 characters")
        String requestId,

        @NotBlank(message = "bot is required")
        @Size(max = 128, message = "bot must be at most 128 characters")
        @Pattern(regexp = "[A-Za-z0-9._-]+",
                message = "bot must contain only letters, digits, dot, underscore or hyphen")
        String bot,

        @Valid
        Subject subject,

        @Size(max = 50, message = "at most 50 parameters may be supplied")
        Map<String, @NotBlank @Size(max = 4000) String> parameters,

        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9._-]*",
                message = "pool must contain only letters, digits, dot, underscore or hyphen")
        String pool,

        @Min(value = 5, message = "waitSeconds must be at least 5")
        @Max(value = 1800, message = "waitSeconds must be at most 1800")
        Integer waitSeconds,

        @Valid
        User user,

        /*
         * Bounded here as well as by decoded size, because this is the one field that can make a
         * request arbitrarily large. Base64 costs a third on top of the bytes it encodes, so this
         * ceiling corresponds to roughly 15 MB of PDF. It is a backstop rather than the real
         * control: by the time a constraint can run, the body is already parsed and in memory, so
         * a hard limit belongs at the container or reverse proxy (see the README).
         */
        @Size(max = 20_971_520, message = "pdfBase64 exceeds the maximum accepted size")
        String pdfBase64) {

    /**
     * Who the run concerns. The NHS number is checked for shape here and for its check digit in
     * the transform step — a ten-digit string that fails the modulus-11 checksum is not a real
     * NHS number, and catching that at the door is much cheaper than a bot searching for a
     * patient who cannot exist.
     *
     * @param nhsNumber ten digits, no spaces or dashes
     * @param localId   optional local/practice identifier for the same person
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Subject(

            @Pattern(regexp = "\\d{10}", message = "nhsNumber must be exactly 10 digits")
            String nhsNumber,

            @Size(max = 64, message = "localId must be at most 64 characters")
            String localId) {

        public boolean isEmpty() {
            return (nhsNumber == null || nhsNumber.isBlank())
                    && (localId == null || localId.isBlank());
        }
    }

    /**
     * Who submitted the request. Named {@code _id} on the wire because that is what the calling
     * system sends; mapped onto a legal Java name rather than propagated inward, so one external
     * convention does not spread through this codebase.
     *
     * <p>The pattern is what makes it safe to build a filename from. A value containing
     * {@code ../}, a path separator or a null byte would otherwise write outside the storage root
     * — this is caller-supplied input that becomes a path, which is the classic way to turn an
     * upload endpoint into arbitrary file write. The check is belt to
     * {@link com.newbrid.epr_automation.gateway.route.DocumentStore}'s braces, which also
     * verifies the resolved path stays under the root.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record User(
            @JsonProperty("_id")
            @NotBlank(message = "user._id is required")
            @Size(max = 64, message = "user._id must be at most 64 characters")
            @Pattern(regexp = "[A-Za-z0-9._-]+",
                    message = "user._id must contain only letters, digits, dot, underscore or hyphen")
            String id) {}

    /** Whether a document was attached at all. Not every run files one. */
    public boolean hasDocument() {
        return pdfBase64 != null && !pdfBase64.isBlank();
    }

    /** Never null, so callers of the transform do not each have to guard the map. */
    public Map<String, String> parametersOrEmpty() {
        return parameters == null ? Map.of() : parameters;
    }
}
