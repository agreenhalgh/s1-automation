package com.newbrid.epr_automation.gateway.route;

import com.newbrid.epr_automation.gateway.GatewayProperties;
import com.newbrid.epr_automation.gateway.api.RunSubmission;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Decides where a submitted PDF goes and checks that it is one, before anything is written.
 *
 * <p>The write itself is done by Camel's {@code file:} component, not here — this class produces
 * the bytes and the destination, and {@code RunGatewayRoute} sends them. Keeping the decision
 * separate from the write is what makes the path rules testable without touching a disk.
 *
 * <h2>Why the path is checked twice</h2>
 * The filename comes from {@code user._id}, which is caller-supplied. Building a path out of
 * caller input is the classic route from "upload endpoint" to "write anywhere on the filesystem":
 * {@code {"user":{"_id":"../../../../etc/cron.d/x"}}}. The record's {@code @Pattern} already
 * excludes separators and dots-only sequences, but a constraint on a request object is the kind
 * of thing that gets relaxed later by someone who does not know it is load-bearing. So the
 * resolved absolute path is verified to sit under the storage root as well, and a mismatch is a
 * rejection rather than a sanitisation — quietly rewriting a hostile path tells the sender their
 * request worked.
 */
@Component
public class DocumentStore {

    /**
     * Every PDF begins with this. Checked because {@code pdfBase64} is a caller-supplied blob that
     * this gateway is about to store under a {@code .pdf} name and hand to a bot to file into a
     * patient record; a name is not evidence of content. It is a sanity check on the wrapper, not
     * a scan of the contents — see the README on the malware-scanning gap.
     */
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    /**
     * One folder per day, in UTC. UTC because everything else in this system is: a document
     * submitted at 00:30 during British Summer Time would otherwise land in the previous day's
     * folder relative to the run record that references it, which is exactly the sort of
     * off-by-one that makes a retention sweep delete the wrong day.
     */
    private static final DateTimeFormatter DATED_FOLDER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final GatewayProperties properties;

    public DocumentStore(GatewayProperties properties) {
        this.properties = properties;
    }

    /**
     * Where a document will be written and what will be written there.
     *
     * @param directory   the dated folder, absolute
     * @param fileName    the leaf name
     * @param content     the decoded PDF
     * @param sha256      hex digest of {@code content}, recorded in the audit trail so the stored
     *                    file can later be shown to be the one that was received
     */
    public record StoredDocument(Path directory, String fileName, byte[] content, String sha256) {

        public Path path() {
            return directory.resolve(fileName);
        }

        public int bytes() {
            return content.length;
        }
    }

    /**
     * Validate the document and work out its destination. Nothing is written; nothing touches the
     * filesystem beyond resolving paths.
     *
     * @param correlationId used to make the filename unique — see {@link #fileNameFor}
     * @throws SubmissionRejectedException if the payload is not a storable PDF, or the destination
     *                                     would fall outside the storage root
     */
    public StoredDocument prepare(RunSubmission submission, String correlationId)
            throws SubmissionRejectedException {

        if (submission.user() == null || submission.user().id() == null
                || submission.user().id().isBlank()) {
            // Not a constraint on the record, because user is only mandatory when a document is
            // attached: a run with no PDF needs no filename and so needs no submitter.
            throw new SubmissionRejectedException(400, "Submission failed validation",
                    List.of("user._id: required when pdfBase64 is supplied, because the stored "
                            + "file is named after it"));
        }

        byte[] content = decode(submission.pdfBase64());
        long max = properties.getDocument().getMaxBytes();
        if (content.length > max) {
            throw new SubmissionRejectedException(413, "Document too large",
                    List.of("pdfBase64: decoded to " + content.length
                            + " bytes, which exceeds the limit of " + max));
        }
        if (!looksLikePdf(content)) {
            throw new SubmissionRejectedException(400, "Submission failed validation",
                    List.of("pdfBase64: does not decode to a PDF (no %PDF- header)"));
        }

        Path root = storageRoot();
        Path directory = root.resolve(DATED_FOLDER.format(Instant.now())).normalize();
        String fileName = fileNameFor(submission.user().id(), correlationId);
        Path destination = directory.resolve(fileName).normalize();

        // Two conditions, and the second is the one that does the work. Containment under the
        // root alone is not enough: "../outside" normalises to a path that is still inside the
        // root but no longer in today's folder, which would let a caller write into the root or
        // into another day's directory and disturb files a retention sweep is counting on. The
        // file has to land exactly in the dated folder and nowhere else.
        if (!destination.startsWith(root) || !directory.equals(destination.getParent())) {
            throw new SubmissionRejectedException(400, "Submission failed validation",
                    List.of("user._id: resolves to a path outside today's document folder"));
        }

        return new StoredDocument(directory, fileName, content, sha256(content));
    }

    /**
     * {@code <user._id>-<correlationId>.pdf}.
     *
     * <p>Named after the submitter as asked, but not <em>only</em> after them. Two submissions
     * from the same user on the same day would otherwise resolve to one filename, and the second
     * would overwrite the first — silently losing a clinical document, which is the worst failure
     * this route could have. The correlation id is the discriminator because it also appears on
     * every audit row for the request, so a file on disk can be traced back to who sent it, when,
     * and what run it produced.
     */
    static String fileNameFor(String userId, String correlationId) {
        return userId + "-" + correlationId + ".pdf";
    }

    /** The configured root, absolute and normalised, so containment checks compare like with like. */
    Path storageRoot() {
        return Path.of(properties.getDocument().getRoot()).toAbsolutePath().normalize();
    }

    private static byte[] decode(String base64) throws SubmissionRejectedException {
        try {
            // MIME decoder rather than the basic one: a caller that wraps its base64 at 76
            // characters, as plenty of libraries do by default, is not making a mistake.
            return Base64.getMimeDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new SubmissionRejectedException(400, "Submission failed validation",
                    List.of("pdfBase64: is not valid base64"));
        }
    }

    private static boolean looksLikePdf(byte[] content) {
        if (content.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (content[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandated by the JDK", e);
        }
    }
}
