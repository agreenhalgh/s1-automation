package com.newbrid.epr_automation.systmone.controlplane.service;

import com.newbrid.epr_automation.systmone.controlplane.domain.AuditEntity;
import com.newbrid.epr_automation.systmone.controlplane.repo.AuditRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes and reads the append-only audit trail (NFR-SEC-05).
 *
 * <p>Reading is the auditor's whole job — "reviews what the automation did and when" — so the
 * query lives here rather than in the controller: the trail is evidence, and a filter that
 * silently drops rows is worse than one that refuses to run. The read methods are
 * {@code readOnly} transactions, which is not an optimisation but a statement: nothing on this
 * path can write, so a viewer cannot alter what it is inspecting.
 */
@Service
@Profile("controlplane")
public class AuditService {

    /** Escape character for LIKE patterns, so a typed % or _ is a literal rather than a wildcard. */
    private static final char ESCAPE = '\\';

    /** Newest first. An auditor opening the trail is almost always asking "what just happened". */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "ts").and(
            // Tie-break on the identity column: entries written inside the same transaction can
            // share a timestamp to the microsecond, and without a second key their relative order
            // is whatever the database felt like.
            Sort.by(Sort.Direction.DESC, "id"));

    private final AuditRepository repo;

    public AuditService(AuditRepository repo) {
        this.repo = repo;
    }

    public void record(String principal, String action, String botId, String runId, String detail) {
        repo.save(new AuditEntity(principal, action, botId, runId, detail));
    }

    /**
     * One page of the trail, newest first, narrowed by whatever the auditor asked for.
     *
     * <p>The ordering is fixed here rather than taken from the caller. A trail is only evidence
     * if two people paging through it see the same rows in the same order, so this is not a
     * default the request gets to override.
     */
    @Transactional(readOnly = true)
    public Page<AuditEntity> search(AuditQuery query, int page, int size) {
        return repo.findAll(specification(query), PageRequest.of(page, size, NEWEST_FIRST));
    }

    /** Every action value present in the trail, for the viewer's filter dropdown. */
    @Transactional(readOnly = true)
    public List<String> actions() {
        return repo.findDistinctActions();
    }

    /** One run's trail in the order it happened — the narrative of a single run. */
    @Transactional(readOnly = true)
    public List<AuditEntity> forRun(String runId) {
        return repo.findByRunIdOrderByTsAsc(runId);
    }

    /**
     * Criteria rather than a string query, so an absent filter contributes no predicate at all
     * instead of a tautology the database has to plan around. An empty conjunction is true, which
     * is what makes {@link AuditQuery#all()} return the whole trail.
     */
    private static Specification<AuditEntity> specification(AuditQuery q) {
        return (root, criteria, cb) -> {
            List<Predicate> predicates = new ArrayList<>(6);
            if (q.principal() != null) {
                predicates.add(cb.like(cb.lower(root.<String>get("principal")),
                        contains(q.principal()), ESCAPE));
            }
            // Exact, unlike the others: the values come from a dropdown of what is actually in
            // the trail, and a substring match on "RUN_FAILED" would also pull in a future
            // "RUN_FAILED_PERMANENTLY" and quietly overstate the count.
            if (q.action() != null) {
                predicates.add(cb.equal(root.<String>get("action"), q.action()));
            }
            if (q.botId() != null) {
                predicates.add(cb.like(cb.lower(root.<String>get("botId")),
                        contains(q.botId()), ESCAPE));
            }
            if (q.runId() != null) {
                predicates.add(cb.like(cb.lower(root.<String>get("runId")),
                        contains(q.runId()), ESCAPE));
            }
            if (q.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<Instant>get("ts"), q.from()));
            }
            if (q.to() != null) {
                predicates.add(cb.lessThan(root.<Instant>get("ts"), q.to()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * A substring match on a value the auditor typed, with LIKE's own metacharacters escaped so
     * they mean themselves. Without this, searching for {@code %} matches every row and {@code _}
     * matches any single character — which cannot hide a row, but can quietly return more than
     * was asked for and make a trail look busier than it is.
     */
    private static String contains(String value) {
        String escaped = value.toLowerCase()
                .replace(String.valueOf(ESCAPE), ESCAPE + "" + ESCAPE)
                .replace("%", ESCAPE + "%")
                .replace("_", ESCAPE + "_");
        return "%" + escaped + "%";
    }
}
