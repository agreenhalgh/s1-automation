package com.newbrid.epr_automation.pdsreview.web;

import com.newbrid.epr_automation.pdsreview.audit.AuditEntry;
import com.newbrid.epr_automation.pdsreview.audit.AuditEvent;
import com.newbrid.epr_automation.pdsreview.audit.AuditRepository;
import com.newbrid.epr_automation.pdsreview.audit.AuditService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * The audit trail, for a role carrying `B0011` and nothing else (FR-AUD-05).
 *
 * <p>Read-only in the strongest sense available: there is no write path here, the repository
 * exposes no update or delete, and {@link AuditEntry} has no setters. The capability check that
 * keeps a reviewer out — and keeps an auditor out of the queue — is in
 * {@code CapabilityInterceptor}.
 *
 * <p><b>Reading this page is itself audited</b> (FR-AUD-07). The trail records which patients were
 * reviewed and by whom, which is precisely the disclosure it exists to record, so an auditor
 * reading it leaves a row like everybody else.
 */
@Controller
public class AuditController {

    private static final int PAGE_SIZE = 50;

    private final AuditRepository entries;
    private final AuditService audit;

    public AuditController(AuditRepository entries, AuditService audit) {
        this.entries = entries;
        this.audit = audit;
    }

    /**
     * One query behind every question an auditor asks — "who looked at this patient?", "what did
     * this reviewer do on Tuesday?", "show me every closure" — because a trail that needs a
     * different screen per question does not get used (FR-AUD-06).
     */
    @GetMapping("/audit")
    @Transactional
    public String trail(@RequestParam(required = false) String actor,
                        @RequestParam(required = false) AuditEvent event,
                        @RequestParam(required = false) String itemKey,
                        @RequestParam(required = false) String nhsNumber,
                        @RequestParam(required = false) String from,
                        @RequestParam(required = false) String to,
                        @RequestParam(defaultValue = "0") int page,
                        Model model) {

        Instant fromInstant = startOfDay(from);
        Instant toInstant = endOfDay(to);

        Page<AuditEntry> results = entries.search(blankToNull(actor), event,
                blankToNull(itemKey), blankToNull(nhsNumber), fromInstant, toInstant,
                PageRequest.of(Math.max(page, 0), PAGE_SIZE));

        model.addAttribute("entries", results);
        model.addAttribute("events", AuditEvent.values());
        model.addAttribute("filterActor", actor);
        model.addAttribute("filterEvent", event);
        model.addAttribute("filterItemKey", itemKey);
        model.addAttribute("filterNhsNumber", nhsNumber);
        model.addAttribute("filterFrom", from);
        model.addAttribute("filterTo", to);

        audit.record(AuditEvent.AUDIT_VIEWED, blankToNull(itemKey), null, blankToNull(nhsNumber),
                "Audit trail read; filters actor=" + nullSafe(actor)
                        + " event=" + (event == null ? "" : event)
                        + " item=" + nullSafe(itemKey)
                        + " nhsNumber=" + nullSafe(nhsNumber)
                        + " from=" + nullSafe(from) + " to=" + nullSafe(to)
                        + "; " + results.getTotalElements() + " entries matched");
        return "audit";
    }

    private static Instant startOfDay(String date) {
        LocalDate parsed = parse(date);
        return parsed == null ? null : parsed.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant endOfDay(String date) {
        LocalDate parsed = parse(date);
        return parsed == null ? null : parsed.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static LocalDate parse(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            // A malformed date filter is not worth an error page: it narrows nothing, and the
            // filter is echoed back so the auditor can see what happened to it.
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
