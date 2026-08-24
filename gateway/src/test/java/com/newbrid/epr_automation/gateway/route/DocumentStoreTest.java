package com.newbrid.epr_automation.gateway.route;

import com.newbrid.epr_automation.gateway.GatewayProperties;
import com.newbrid.epr_automation.gateway.api.RunSubmission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where a submitted PDF goes, and — mostly — what must never be allowed to decide that.
 *
 * <p>The filename comes from {@code user._id}, which is caller-supplied. Most of these tests exist
 * because building a path out of caller input is how an upload endpoint becomes an arbitrary file
 * write, and a rule that is only enforced by an annotation on a request record is one edit away
 * from not being enforced at all.
 */
class DocumentStoreTest {

    @TempDir
    Path storageRoot;

    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);
    private static final String CORRELATION = "gw-11111111-2222-3333-4444-555555555555";

    private DocumentStore store() {
        GatewayProperties properties = new GatewayProperties();
        properties.getDocument().setRoot(storageRoot.toString());
        return new DocumentStore(properties);
    }

    private static RunSubmission submission(String userId, String base64) {
        return new RunSubmission(null, "file-a-letter", null, null, null, null,
                userId == null ? null : new RunSubmission.User(userId), base64);
    }

    private static String encoded(byte[] content) {
        return Base64.getEncoder().encodeToString(content);
    }

    // ---- where it goes --------------------------------------------------------------

    @Test
    void writesUnderADatedFolderNamedForTheSubmitter() throws Exception {
        DocumentStore.StoredDocument stored =
                store().prepare(submission("user-42", encoded(PDF)), CORRELATION);

        String today = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneOffset.UTC).format(Instant.now());

        assertThat(stored.directory()).isEqualTo(storageRoot.resolve(today));
        assertThat(stored.fileName())
                .as("named after user._id, as asked")
                .startsWith("user-42-")
                .endsWith(".pdf");
        assertThat(stored.content()).isEqualTo(PDF);
    }

    /**
     * The date folder is UTC, not local. A document submitted at 00:30 during British Summer Time
     * would otherwise land in the previous day's folder relative to the run record referencing it
     * — the sort of off-by-one that makes a retention sweep delete the wrong day.
     */
    @Test
    void theDatedFolderIsUtc() throws Exception {
        DocumentStore.StoredDocument stored =
                store().prepare(submission("user-42", encoded(PDF)), CORRELATION);

        assertThat(stored.directory().getFileName().toString())
                .isEqualTo(DateTimeFormatter.ofPattern("yyyy-MM-dd")
                        .withZone(ZoneOffset.UTC).format(Instant.now()));
    }

    /**
     * Named after the submitter but not only after them. Two documents from one user on one day
     * must not resolve to one filename — the second would overwrite the first and silently lose a
     * clinical document.
     */
    @Test
    void twoSubmissionsFromOneUserOnOneDayDoNotCollide() throws Exception {
        DocumentStore store = store();

        String first = store.prepare(submission("user-42", encoded(PDF)), "gw-aaaa").fileName();
        String second = store.prepare(submission("user-42", encoded(PDF)), "gw-bbbb").fileName();

        assertThat(first).isNotEqualTo(second);
        assertThat(first).contains("user-42");
        assertThat(second).contains("user-42");
    }

    @Test
    void theFilenameCarriesTheCorrelationIdSoAFileOnDiskCanBeTracedToItsRequest() {
        assertThat(DocumentStore.fileNameFor("user-42", CORRELATION))
                .isEqualTo("user-42-" + CORRELATION + ".pdf");
    }

    // ---- what must not decide where it goes ----------------------------------------

    /**
     * The attack this route would otherwise be wide open to. Each of these is a {@code user._id}
     * that, concatenated into a path, escapes the storage root.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "../../../../etc/cron.d/evil",
            "..\\..\\..\\windows\\system32\\evil",
            "../outside",
            "a/../../b",
            "/etc/passwd",
            "C:/windows/evil"
    })
    void refusesASubmitterIdThatWouldEscapeTheStorageRoot(String hostileId) {
        assertThatThrownBy(() -> store().prepare(submission(hostileId, encoded(PDF)), CORRELATION))
                .isInstanceOf(SubmissionRejectedException.class)
                .as("rejected, not sanitised — quietly rewriting a hostile path tells the sender "
                        + "their request worked")
                .hasMessageContaining("validation");
    }

    /**
     * Dots are legal inside an id and stay legal: {@code ..} as a submitter id becomes the leaf
     * {@code ..-<correlationId>.pdf}, an ordinary file in the right folder rather than a
     * traversal. Rejecting it would be a rule about punctuation rather than about paths.
     */
    @Test
    void dotsWithinAnIdAreFineBecauseTheyStayALeafName() throws Exception {
        DocumentStore.StoredDocument stored =
                store().prepare(submission("..", encoded(PDF)), CORRELATION);

        assertThat(stored.path().getParent()).isEqualTo(stored.directory());
        assertThat(stored.fileName()).isEqualTo("..-" + CORRELATION + ".pdf");
    }

    /** Escaping the dated folder without escaping the root is still an escape. */
    @Test
    void refusesAnIdThatLandsInTheRootRatherThanTodaysFolder() {
        assertThatThrownBy(() -> store().prepare(submission("../escaped", encoded(PDF)), CORRELATION))
                .isInstanceOf(SubmissionRejectedException.class);
    }

    @Test
    void aRejectedPathIsReportedAsABadRequestAndNotAsAServerError() {
        assertThatThrownBy(() -> store().prepare(submission("../escape", encoded(PDF)), CORRELATION))
                .isInstanceOf(SubmissionRejectedException.class)
                .satisfies(e -> assertThat(((SubmissionRejectedException) e).httpStatus())
                        .isEqualTo(400));
    }

    // ---- what is stored ------------------------------------------------------------

    @Test
    void refusesAPayloadThatIsNotAPdf() {
        String notAPdf = Base64.getEncoder()
                .encodeToString("<html>definitely not a pdf</html>".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> store().prepare(submission("user-42", notAPdf), CORRELATION))
                .isInstanceOf(SubmissionRejectedException.class)
                .hasMessageContaining("validation")
                .satisfies(e -> assertThat(((SubmissionRejectedException) e).problems())
                        .anySatisfy(p -> assertThat(p).contains("%PDF-")));
    }

    @Test
    void refusesSomethingThatIsNotBase64() {
        assertThatThrownBy(() -> store().prepare(submission("user-42", "!!! not base64 !!!"), CORRELATION))
                .isInstanceOf(SubmissionRejectedException.class)
                .satisfies(e -> assertThat(((SubmissionRejectedException) e).problems())
                        .anySatisfy(p -> assertThat(p).contains("not valid base64")));
    }

    /** Plenty of libraries wrap base64 at 76 characters by default. That is not a malformed request. */
    @Test
    void acceptsBase64WrappedAcrossLines() throws Exception {
        byte[] big = new byte[4096];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        String wrapped = Base64.getMimeEncoder().encodeToString(big);

        assertThat(wrapped).contains("\n");
        assertThat(store().prepare(submission("user-42", wrapped), CORRELATION).bytes())
                .isEqualTo(big.length);
    }

    @Test
    void refusesADocumentOverTheConfiguredLimit() {
        GatewayProperties properties = new GatewayProperties();
        properties.getDocument().setRoot(storageRoot.toString());
        properties.getDocument().setMaxBytes(100);
        DocumentStore small = new DocumentStore(properties);

        byte[] tooBig = new byte[500];
        System.arraycopy(PDF, 0, tooBig, 0, PDF.length);

        assertThatThrownBy(() -> small.prepare(submission("user-42", encoded(tooBig)), CORRELATION))
                .isInstanceOf(SubmissionRejectedException.class)
                .satisfies(e -> assertThat(((SubmissionRejectedException) e).httpStatus())
                        .as("too large is 413, not a generic 400")
                        .isEqualTo(413));
    }

    @Test
    void requiresASubmitterWhenADocumentIsAttached() {
        assertThatThrownBy(() -> store().prepare(submission(null, encoded(PDF)), CORRELATION))
                .isInstanceOf(SubmissionRejectedException.class)
                .satisfies(e -> assertThat(((SubmissionRejectedException) e).problems())
                        .anySatisfy(p -> assertThat(p).contains("user._id")));
    }

    @Test
    void recordsADigestSoTheStoredFileCanBeShownToBeTheOneReceived() throws Exception {
        DocumentStore.StoredDocument stored =
                store().prepare(submission("user-42", encoded(PDF)), CORRELATION);

        assertThat(stored.sha256())
                .hasSize(64)
                .matches("[0-9a-f]+")
                .isEqualTo(store().prepare(submission("user-42", encoded(PDF)), CORRELATION).sha256());
    }
}
