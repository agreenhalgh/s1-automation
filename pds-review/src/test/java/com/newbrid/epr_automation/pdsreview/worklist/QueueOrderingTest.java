package com.newbrid.epr_automation.pdsreview.worklist;

import com.newbrid.epr_automation.pdsreview.config.PdsReviewProperties;
import com.newbrid.epr_automation.pdsreview.domain.WaitBasis;
import com.newbrid.epr_automation.pdsreview.presentation.PresentationView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the queue is ordered (FR-WL-02 … FR-WL-05).
 *
 * <p>The case worth guarding hardest is the unscored one. A presentation with no {@code pValue}
 * must sort <em>above</em> P0, not below P5: a missing score is an absence of evidence, not
 * evidence that somebody can wait, and a queue that buries the patients nobody managed to score
 * hides its worst cases (FR-WL-04, NFR-SAFE-02).
 */
class QueueOrderingTest {

    private static final JsonMapper JSON = new JsonMapper();

    private final QueueOrdering ordering = new QueueOrdering(properties("top"));

    @Test
    void ranksMostUrgentFirst() {
        assertThat(ordering.rankFor("P0")).isLessThan(ordering.rankFor("P1"));
        assertThat(ordering.rankFor("P1")).isLessThan(ordering.rankFor("P5"));
    }

    @Test
    void anUnscoredItemSortsAboveTheMostUrgentScoredOne() {
        assertThat(ordering.rankFor(null))
                .as("a missing score is not evidence of low urgency")
                .isLessThan(ordering.rankFor("P0"));
    }

    @Test
    void aScoreNobodyRecognisesIsTreatedAsUnscored() {
        // If a P6 or a U1 turns up from upstream, nobody here knows what it means. Guessing its
        // urgency from its alphabetical position would be worse than admitting ignorance.
        assertThat(ordering.rankFor("P6")).isEqualTo(ordering.rankFor(null));
        assertThat(ordering.scored("P6")).isFalse();
        assertThat(ordering.scored("P3")).isTrue();
    }

    @Test
    void toleratesCaseAndSpacing() {
        assertThat(ordering.rankFor(" p3 ")).isEqualTo(ordering.rankFor("P3"));
    }

    @Test
    void theBandCanBeConfiguredToTheBottomIfSomebodyDecidesThat() {
        // The rule is configuration (FR-WL-05), so it can be changed under review — but the
        // default is the safe one, which is what the test above pins.
        QueueOrdering bottomBand = new QueueOrdering(properties("bottom"));

        assertThat(bottomBand.rankFor(null)).isGreaterThan(bottomBand.rankFor("P5"));
    }

    // ---- which clock the wait is measured from (FR-WL-03) ----------------------------

    @Test
    void waitsFromWhenThePatientFinishedTriage() {
        Instant received = Instant.parse("2026-06-24T17:00:00Z");
        PresentationView presentation = presentationWithTriageEnd("2026-06-24T16:41:13.930Z");

        QueueOrdering.Wait wait = ordering.waitFrom(presentation, received);

        assertThat(wait.basis()).isEqualTo(WaitBasis.TRIAGE_END);
        assertThat(wait.since()).isEqualTo(Instant.parse("2026-06-24T16:41:13.930Z"));
    }

    @Test
    void fallsBackToReceiptAndSaysSo() {
        Instant received = Instant.parse("2026-06-24T17:00:00Z");

        QueueOrdering.Wait wait = ordering.waitFrom(presentationWithTriageEnd(null), received);

        assertThat(wait.basis())
                .as("the row shows which clock was used, rather than implying the triage one")
                .isEqualTo(WaitBasis.RECEIPT);
        assertThat(wait.since()).isEqualTo(received);
    }

    @Test
    void anUnreadableTimestampIsNotAReasonToRejectAPresentation() {
        Instant received = Instant.parse("2026-06-24T17:00:00Z");

        QueueOrdering.Wait wait = ordering.waitFrom(presentationWithTriageEnd("yesterday"), received);

        assertThat(wait.basis()).isEqualTo(WaitBasis.RECEIPT);
    }

    @Test
    void describesADurationTheWayAReviewerReadsIt() {
        assertThat(QueueOrdering.describe(Duration.ofMinutes(4))).isEqualTo("4m");
        assertThat(QueueOrdering.describe(Duration.ofMinutes(80))).isEqualTo("1h 20m");
    }

    private static PdsReviewProperties properties(String band) {
        PdsReviewProperties.Queue queue = new PdsReviewProperties.Queue(
                List.of("P0", "P1", "P2", "P3", "P4", "P5"), band, "triage-end",
                Duration.ofSeconds(30), Duration.ofMinutes(5));
        return new PdsReviewProperties(null, null, queue, null, null, null, null);
    }

    private static PresentationView presentationWithTriageEnd(String endTimestamp) {
        String json = endTimestamp == null
                ? """
                  {"externalValue":{"user":{"_id":"abc"}}}
                  """
                : """
                  {"externalValue":{"user":{"_id":"abc","endTimestamp":"%s"}}}
                  """.formatted(endTimestamp);
        return new PresentationView(JSON.readTree(json));
    }
}
