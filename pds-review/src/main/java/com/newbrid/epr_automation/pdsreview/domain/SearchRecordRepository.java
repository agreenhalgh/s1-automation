package com.newbrid.epr_automation.pdsreview.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SearchRecordRepository extends JpaRepository<SearchRecord, Long> {

    /** Oldest first: this is the path the reviewer took, and order is the point (FR-REV-10). */
    List<SearchRecord> findByItemIdOrderByRunAtAsc(Long itemId);

    List<SearchRecord> findBySessionIdOrderByRunAtAsc(Long sessionId);

    long countByItemIdAndKind(Long itemId, SearchKind kind);
}
