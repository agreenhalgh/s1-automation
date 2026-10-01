package com.newbrid.epr_automation.pdsreview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One search, as it was actually run (FR-AUD-02). Every execution is a row: re-running the same
 * search with an edited parameter is a second search, not an amendment of the first.
 *
 * <p>Two details earn their own columns. The **submitted and sent postcode** are both kept,
 * because the value that goes to PDS is normalised (FR-PDS-05a) and a nil return has to be
 * distinguishable from a normalisation that changed the question. And the **outcome** is an enum
 * rather than "did it return rows", because "no matches", "too many matches" and "PDS did not
 * answer" lead a reviewer to different conclusions (FR-PDS-09, FR-PDS-12).
 *
 * <p>This is also the evidence a decision points at (FR-DEC-05), and what makes the reviewer's
 * chosen path through the three searches recoverable now that the order is only a default
 * (FR-REV-09, FR-REV-10).
 */
@Entity
@Table(name = "search_record", indexes = {
        @Index(name = "idx_search_item", columnList = "itemId, runAt"),
        @Index(name = "idx_search_session", columnList = "sessionId")
})
public class SearchRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long itemId;

    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SearchKind kind;

    /** The query as sent, canonicalised — the exact parameters, in a stable order. */
    @Column(nullable = false, length = 2000)
    private String parameters;

    /** The postcode the patient gave, before normalisation. Null when none was in play. */
    @Column(length = 40)
    private String submittedPostcode;

    /** The postcode actually sent. Null when it was not sent — see FR-PDS-05b. */
    @Column(length = 16)
    private String sentPostcode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SearchOutcome outcome;

    private int resultCount;

    /** The best score PDS returned, where it returned any. 1.0 is an exact match. */
    private Double topScore;

    /** Scores of the candidates returned, highest first, for the trail. */
    @Column(length = 500)
    private String scores;

    private long elapsedMs;

    @Column(nullable = false, length = 128)
    private String runBy;

    @Column(length = 64)
    private String runByRole;

    @Column(nullable = false)
    private Instant runAt;

    /** Why nothing was sent, or what went wrong. Never a substitute for {@link #outcome}. */
    @Column(length = 2000)
    private String detail;

    protected SearchRecord() {}

    public static SearchRecord of(Long itemId, Long sessionId, SearchKind kind, String parameters,
                                  String runBy, String runByRole, Instant runAt) {
        SearchRecord record = new SearchRecord();
        record.itemId = itemId;
        record.sessionId = sessionId;
        record.kind = kind;
        record.parameters = parameters;
        record.runBy = runBy;
        record.runByRole = runByRole;
        record.runAt = runAt;
        record.outcome = SearchOutcome.NOT_ATTEMPTED;
        return record;
    }

    public void postcode(String submitted, String sent) {
        this.submittedPostcode = submitted;
        this.sentPostcode = sent;
    }

    public void completed(SearchOutcome outcome, int resultCount, Double topScore, String scores,
                          long elapsedMs, String detail) {
        this.outcome = outcome;
        this.resultCount = resultCount;
        this.topScore = topScore;
        this.scores = scores;
        this.elapsedMs = elapsedMs;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public Long getItemId() { return itemId; }
    public Long getSessionId() { return sessionId; }
    public SearchKind getKind() { return kind; }
    public String getParameters() { return parameters; }
    public String getSubmittedPostcode() { return submittedPostcode; }
    public String getSentPostcode() { return sentPostcode; }
    public SearchOutcome getOutcome() { return outcome; }
    public int getResultCount() { return resultCount; }
    public Double getTopScore() { return topScore; }
    public String getScores() { return scores; }
    public long getElapsedMs() { return elapsedMs; }
    public String getRunBy() { return runBy; }
    public String getRunByRole() { return runByRole; }
    public Instant getRunAt() { return runAt; }
    public String getDetail() { return detail; }
}
