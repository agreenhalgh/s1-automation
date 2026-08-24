package com.newbrid.epr_automation.systmone.controlplane.web.ui;

import com.newbrid.epr_automation.systmone.controlplane.domain.AuditEntity;
import com.newbrid.epr_automation.systmone.controlplane.service.AuditQuery;
import com.newbrid.epr_automation.systmone.controlplane.service.AuditService;
import com.newbrid.epr_automation.systmone.controlplane.web.dto.WebDtos;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * The audit log viewer (NFR-SEC-05): who ran what bot, when, and what came of it. This is the
 * Auditor persona's whole surface — "reviews what the automation did and when" — and it is
 * strictly read-only. There is no edit, no delete and no re-dispatch anywhere on it, because
 * the value of an audit trail is precisely that the person reading it cannot change it.
 *
 * <p><b>AUDITOR only.</b> Guarded twice on purpose: by URL in
 * {@link com.newbrid.epr_automation.systmone.controlplane.security.SecurityConfig} and by
 * {@link PreAuthorize} here. The two are not redundant in the way they look — the URL rule is
 * what stops an unauthorised request reaching the controller at all, and the annotation is what
 * keeps the rule attached to the code if a later refactor moves, renames or adds a mapping. An
 * audit surface is the wrong place to rely on a path pattern in another file staying in step.
 *
 * <p>Note that this is a role check, not a seniority check: ADMIN does not inherit it. Whether
 * an administrator should be able to read the trail of the system they administer is an
 * information-governance decision rather than a technical one, and the narrower default is the
 * one that can be widened later without having already leaked anything.
 */
@Controller
@Profile("controlplane")
@PreAuthorize("hasRole('AUDITOR')")
public class AuditController {

    /** Enough to scan, small enough that the detail column stays readable. */
    private static final int PAGE_SIZE = 50;

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/audit")
    public String audit(@RequestParam(required = false) String principal,
                        @RequestParam(required = false) String action,
                        @RequestParam(required = false) String botId,
                        @RequestParam(required = false) String runId,
                        @RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                        @RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                        @RequestParam(defaultValue = "0") int page,
                        Model model) {

        AuditQuery query = new AuditQuery(principal, action, botId, runId, utc(from), utc(to));
        Page<AuditEntity> found = audit.search(query, Math.max(0, page), PAGE_SIZE);

        model.addAttribute("rows", WebDtos.AuditRow.of(found.getContent()));
        model.addAttribute("page", found);
        model.addAttribute("actions", audit.actions());
        model.addAttribute("query", query);
        // Echoed back so the form redisplays what was asked for and the paging links can carry
        // it. The bound LocalDateTime is what the datetime-local input round-trips, not the
        // Instant the query ran against.
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        return "audit";
    }

    /**
     * A {@code datetime-local} input carries no zone, so one has to be chosen. UTC, to match the
     * stored timestamps and the {@code PGTZ=UTC} database: reading the box as the reader's local
     * time would mean the same typed range selected different rows for two auditors in different
     * places. The form labels the fields UTC so the choice is visible rather than assumed.
     */
    private static Instant utc(LocalDateTime local) {
        return local == null ? null : local.toInstant(ZoneOffset.UTC);
    }
}
