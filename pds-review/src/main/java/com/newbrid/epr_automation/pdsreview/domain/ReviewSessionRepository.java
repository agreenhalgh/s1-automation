package com.newbrid.epr_automation.pdsreview.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewSessionRepository extends JpaRepository<ReviewSession, Long> {

    /** Newest first — what the next reviewer is shown so they do not repeat the last one's work. */
    List<ReviewSession> findByItemIdOrderByStartedAtDesc(Long itemId);

    Optional<ReviewSession> findFirstByItemIdAndEndedAtIsNullOrderByStartedAtDesc(Long itemId);
}
