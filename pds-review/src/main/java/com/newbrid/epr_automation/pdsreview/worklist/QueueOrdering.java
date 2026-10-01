package com.newbrid.epr_automation.pdsreview.worklist;

import com.newbrid.epr_automation.pdsreview.config.PdsReviewProperties;
import com.newbrid.epr_automation.pdsreview.domain.WaitBasis;
import com.newbrid.epr_automation.pdsreview.presentation.PresentationView;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * Turns a triage score into a queue position, and a triage timestamp into a waiting time.
 *
 * <p>The ordering is configuration (FR-WL-05) because it encodes a clinical judgement about which
 * patient should be seen first, and changing that should be a reviewed change rather than a code
 * edit. The rank is computed once, on admission, and stored on the item so the database can order
 * and page ten thousand rows instead of the application loading them all to sort (NFR-PERF-01).
 *
 * <p><b>An unscored item sorts to the top.</b> That is the one rule here worth arguing about, and
 * FR-WL-04 settles it: a missing {@code pValue} is an absence of evidence, not evidence of low
 * urgency, and a queue that quietly buries the patients nobody managed to score is a queue that
 * hides its worst cases (NFR-SAFE-02).
 */
@Component
public class QueueOrdering {

    /**
     * The gap left between the unscored band and the scored ones. Wide enough that the two bands
     * can never interleave, whatever the configured score list contains.
     */
    private static final int BAND_GAP = 1_000;

    private final PdsReviewProperties.Queue config;

    public QueueOrdering(PdsReviewProperties properties) {
        this.config = properties.queue();
    }

    /**
     * Where this score sorts. Lower is more urgent.
     *
     * <p>A score the configuration does not list is treated as unscored rather than as its
     * alphabetical position — if a {@code P6} or a {@code U1} appears upstream, nobody here knows
     * what it means, and guessing its urgency is exactly the wrong response.
     */
    public int rankFor(String triageScore) {
        List<String> order = config.triageScoreOrder();
        int index = triageScore == null ? -1 : indexOfIgnoringCase(order, triageScore.trim());
        if (index >= 0) {
            return config.unscoredAtTop() ? BAND_GAP + index : index;
        }
        return config.unscoredAtTop() ? 0 : BAND_GAP + order.size();
    }

    /** Whether this score is one the configuration recognises. */
    public boolean scored(String triageScore) {
        return triageScore != null
                && indexOfIgnoringCase(config.triageScoreOrder(), triageScore.trim()) >= 0;
    }

    /**
     * When this patient started waiting, and from which clock (FR-WL-03). A patient who finished
     * triage forty minutes ago has been waiting forty minutes, whatever time the payload reached
     * us — so the triage end is preferred, and the fallback is recorded rather than hidden.
     */
    public Wait waitFrom(PresentationView presentation, Instant receivedAt) {
        if (config.waitFromTriageEnd()) {
            Optional<Instant> triageEnd = parse(presentation.triageEndTimestamp());
            if (triageEnd.isPresent()) {
                return new Wait(triageEnd.get(), WaitBasis.TRIAGE_END);
            }
        }
        return new Wait(receivedAt, WaitBasis.RECEIPT);
    }

    /** @param since when the clock started, @param basis which clock it was */
    public record Wait(Instant since, WaitBasis basis) {}

    /** How long an item has been waiting, for the worklist row and the ageing indicator. */
    public static Duration waited(Instant since, Instant now) {
        return since == null ? Duration.ZERO : Duration.between(since, now);
    }

    /** "1h 20m", "4m" — a duration a reviewer reads at a glance rather than parses. */
    public static String describe(Duration waited) {
        long hours = waited.toHours();
        long minutes = waited.toMinutesPart();
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    private static Optional<Instant> parse(Optional<String> timestamp) {
        if (timestamp.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(timestamp.get()));
        } catch (DateTimeParseException e) {
            // A timestamp we cannot read is not a reason to reject a presentation; it just means
            // the receipt clock is used instead, which the row will say.
            return Optional.empty();
        }
    }

    private static int indexOfIgnoringCase(List<String> values, String wanted) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).equalsIgnoreCase(wanted)) {
                return i;
            }
        }
        return -1;
    }
}
