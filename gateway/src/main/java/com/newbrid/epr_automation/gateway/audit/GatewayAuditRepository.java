package com.newbrid.epr_automation.gateway.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Append and read only. Nothing in the gateway calls {@code delete}, and the trail is written
 * exclusively through {@link GatewayAuditService} so that stays true.
 */
public interface GatewayAuditRepository extends JpaRepository<GatewayAuditEntity, Long> {

    /** One request's stages in the order they happened. */
    List<GatewayAuditEntity> findByCorrelationIdOrderByTsAsc(String correlationId);

    /** Every row for a run, for tying a control-plane run back to the request that caused it. */
    List<GatewayAuditEntity> findByRunIdOrderByTsAsc(String runId);
}
