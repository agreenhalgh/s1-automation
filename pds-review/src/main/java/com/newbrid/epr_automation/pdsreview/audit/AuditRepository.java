package com.newbrid.epr_automation.pdsreview.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Append-only by construction: this interface exposes no update and no delete, and
 * {@link AuditEntry} has no setters (FR-AUD-05). Removing rows is a database-level retention job
 * with its own authorisation, not something an application path can reach.
 */
public interface AuditRepository extends JpaRepository<AuditEntry, Long> {

    /**
     * The auditor's search (FR-AUD-06). Every filter is optional, so one query answers "who
     * looked at this patient?", "what did this reviewer do on Tuesday?" and "show me every
     * closure" without three code paths.
     */
    @Query("""
            select a from AuditEntry a
            where (:actor is null or a.actor = :actor)
              and (:event is null or a.event = :event)
              and (:itemKey is null or a.itemKey = :itemKey)
              and (:nhsNumber is null or a.nhsNumber = :nhsNumber)
              and (:from is null or a.at >= :from)
              and (:to is null or a.at <= :to)
            order by a.at desc, a.id desc
            """)
    Page<AuditEntry> search(@Param("actor") String actor,
                            @Param("event") AuditEvent event,
                            @Param("itemKey") String itemKey,
                            @Param("nhsNumber") String nhsNumber,
                            @Param("from") Instant from,
                            @Param("to") Instant to,
                            Pageable pageable);
}
