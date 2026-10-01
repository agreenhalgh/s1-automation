package com.newbrid.epr_automation.pdsreview.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReviewItemRepository extends JpaRepository<ReviewItem, Long> {

    Optional<ReviewItem> findByItemKey(String itemKey);

    boolean existsByItemKey(String itemKey);

    /**
     * The worklist, in clinical order: most urgent first, oldest first within a score (FR-WL-02).
     * Both keys are columns so the database orders and pages ten thousand items rather than the
     * application loading them all to sort (NFR-PERF-01).
     *
     * <p>Decided items are excluded (FR-WL-06). Items held by another reviewer are <em>not</em> —
     * they are shown, and the template refuses to open them, so a reviewer can see that somebody
     * is already on it rather than wondering where it went.
     */
    @Query("""
            select i from ReviewItem i
            where i.state <> com.newbrid.epr_automation.pdsreview.domain.ReviewState.DECIDED
              and (:hospital is null or i.hospitalName = :hospital)
              and (:department is null or i.departmentCode = :department)
              and (:score is null or i.triageScore = :score)
              and (:reason is null or i.unmatchedReason = :reason)
            order by i.priorityRank asc, i.waitingSince asc
            """)
    Page<ReviewItem> queue(@Param("hospital") String hospital,
                           @Param("department") String department,
                           @Param("score") String score,
                           @Param("reason") UnmatchedReason reason,
                           Pageable pageable);

    /**
     * Read for a claim. A pessimistic write lock rather than an optimistic retry: two reviewers
     * opening the same item is the one race that matters here, because two people matching the
     * same patient independently is how one patient becomes two records (FR-REV-01). The row is
     * held for microseconds, and being certain is worth more than the contention it costs.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ReviewItem i where i.id = :id")
    Optional<ReviewItem> findByIdForUpdate(@Param("id") Long id);

    /** Locks that ran out while nobody was looking (FR-REV-02). */
    @Query("""
            select i from ReviewItem i
            where i.state = com.newbrid.epr_automation.pdsreview.domain.ReviewState.IN_REVIEW
              and i.lockedUntil < :now
            """)
    List<ReviewItem> expiredLocks(@Param("now") Instant now);

    /** The outbox: decisions written down but not yet acknowledged (FR-DEC-07). */
    @Query("""
            select i from ReviewItem i
            where i.postBackState = com.newbrid.epr_automation.pdsreview.domain.PostBackState.PENDING
              and (i.postBackNextAttemptAt is null or i.postBackNextAttemptAt <= :now)
            order by i.decidedAt asc
            """)
    List<ReviewItem> awaitingPostBack(@Param("now") Instant now, Pageable pageable);

    long countByState(ReviewState state);

    long countByStateAndTriageScore(ReviewState state, String triageScore);

    long countByPostBackState(PostBackState postBackState);

    long countByDecision(Decision decision);

    /**
     * The oldest thing still waiting — the number a service manager should be alerted on
     * (NFR-OBS-02), and the one that says whether the queue is coping.
     */
    @Query("""
            select min(i.waitingSince) from ReviewItem i
            where i.state <> com.newbrid.epr_automation.pdsreview.domain.ReviewState.DECIDED
            """)
    Instant oldestWaitingSince();

    /**
     * Earlier decided items for the same patient details, so a repeat presentation shows the
     * reviewer what was decided last time rather than starting from nothing (FR-INT-07).
     */
    @Query("""
            select i from ReviewItem i
            where i.state = com.newbrid.epr_automation.pdsreview.domain.ReviewState.DECIDED
              and i.statedName = :name
              and i.statedDateOfBirth = :dateOfBirth
              and i.id <> :excludeId
            order by i.decidedAt desc
            """)
    List<ReviewItem> earlierDecisionsFor(@Param("name") String statedName,
                                         @Param("dateOfBirth") String statedDateOfBirth,
                                         @Param("excludeId") Long excludeId,
                                         Pageable pageable);
}
