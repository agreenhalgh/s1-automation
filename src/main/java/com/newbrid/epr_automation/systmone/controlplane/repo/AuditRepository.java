package com.newbrid.epr_automation.systmone.controlplane.repo;

import com.newbrid.epr_automation.systmone.controlplane.domain.AuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * The audit trail is append-only (NFR-SEC-05). Nothing here updates or deletes a row, and the
 * inherited {@code save}/{@code delete} methods are not used outside {@code AuditService.record}.
 *
 * <p>{@link JpaSpecificationExecutor} rather than a derived query with optional parameters: the
 * viewer's filters are all optional and independent, and expressing that as
 * {@code :param is null or ...} both reads badly and leaves PostgreSQL to infer the type of a
 * parameter it only ever sees compared to null.
 */
public interface AuditRepository extends JpaRepository<AuditEntity, Long>,
        JpaSpecificationExecutor<AuditEntity> {

    List<AuditEntity> findByRunIdOrderByTsAsc(String runId);

    /**
     * The action values actually present, for the viewer's dropdown. Read from the data rather
     * than from a hardcoded list so an action added by a later service — and any action written
     * by a version of the application that has since been deployed over — still appears.
     */
    @Query("select distinct a.action from AuditEntity a where a.action is not null order by a.action")
    List<String> findDistinctActions();
}
