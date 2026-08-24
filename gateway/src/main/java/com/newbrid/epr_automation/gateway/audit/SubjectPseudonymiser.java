package com.newbrid.epr_automation.gateway.audit;

import com.newbrid.epr_automation.gateway.GatewayProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Turns a patient identifier into an audit reference (NFR-SEC-05: "by reference, not by copying
 * clinical data").
 *
 * <p>A keyed HMAC rather than a plain hash, and the distinction is not academic. There are only
 * ten billion possible NHS numbers, and fewer than a billion issued; an unkeyed SHA-256 of one
 * can be reversed by generating every candidate and comparing, which is minutes of work on a
 * laptop. A digest of a low-entropy identifier is not a pseudonym, it is an encoding. The HMAC
 * key is what makes the reference useless to anyone who does not hold it.
 *
 * <p>The same input always yields the same reference, which is the property that lets an auditor
 * follow one patient's requests through the trail without the trail naming them. Rotating the key
 * deliberately severs that link across the rotation.
 */
@Component
public class SubjectPseudonymiser {

    private static final String ALGORITHM = "HmacSHA256";

    /** Enough to make collisions irrelevant at any plausible trail size, short enough to read. */
    private static final int REFERENCE_HEX_CHARS = 32;

    private final SecretKeySpec key;

    public SubjectPseudonymiser(GatewayProperties properties) {
        this.key = new SecretKeySpec(
                properties.getAudit().getPseudonymKey().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /**
     * A reference for one identifier, or null if there is nothing to reference. The type prefix
     * keeps an NHS number and a local id that happen to share digits from colliding into one
     * reference, and makes a reference self-describing when it appears in the trail.
     */
    public String reference(String type, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal((type + ":" + identifier.trim()).getBytes(StandardCharsets.UTF_8));
            return type + "-" + HexFormat.of().formatHex(digest).substring(0, REFERENCE_HEX_CHARS);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is mandated by the JDK and the key is a non-empty byte array, so neither
            // can happen. Failing loudly beats returning null, which would silently write an
            // audit row with no subject reference at all.
            throw new IllegalStateException("Cannot compute an audit subject reference", e);
        }
    }
}
