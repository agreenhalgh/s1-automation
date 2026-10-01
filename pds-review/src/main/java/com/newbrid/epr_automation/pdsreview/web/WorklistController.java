package com.newbrid.epr_automation.pdsreview.web;

import com.newbrid.epr_automation.pdsreview.audit.AuditEvent;
import com.newbrid.epr_automation.pdsreview.audit.AuditService;
import com.newbrid.epr_automation.pdsreview.config.PdsReviewProperties;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItem;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItemRepository;
import com.newbrid.epr_automation.pdsreview.domain.UnmatchedReason;
import com.newbrid.epr_automation.pdsreview.worklist.QueueOrdering;
import com.newbrid.epr_automation.pdsreview.worklist.QueueSignature;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The worklist: who is waiting, most urgent first.
 *
 * <p>Ordering is done by the database on two indexed columns (FR-WL-02), not in memory, because
 * the queue may be ten thousand deep and the first page still has to render in a second
 * (NFR-PERF-01). The rank was computed when the item was admitted, so the ordering rule is
 * applied once per presentation rather than once per page view.
 *
 * <p>Two endpoints, one list. {@link #worklist} renders the page; {@link #rows} renders only the
 * list, for the background refresh that keeps it current without the reviewer acting (FR-WL-09,
 * FR-WL-11). The refresh is deliberately dull: it sends the signature of what is on screen and
 * usually gets {@code 204 No Content} back, which is what keeps an open worklist from generating
 * a page of audit entries an hour (FR-WL-11a).
 */
@Controller
public class WorklistController {

    private static final int PAGE_SIZE = 25;

    /** A view that writes nothing, for the 204 the refresh usually gets (FR-WL-11a). */
    private static final String NOTHING_TO_SEND = "fragments/nothing :: nothing";

    private final ReviewItemRepository items;
    private final AuditService audit;
    private final PdsReviewProperties properties;
    private final Clock clock;

    public WorklistController(ReviewItemRepository items, AuditService audit,
                              PdsReviewProperties properties, Clock clock) {
        this.items = items;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping("/worklist")
    @Transactional
    public String worklist(@RequestParam(required = false) String hospital,
                           @RequestParam(required = false) String department,
                           @RequestParam(required = false) String score,
                           @RequestParam(required = false) UnmatchedReason reason,
                           @RequestParam(defaultValue = "0") int page,
                           Model model) {

        Page<ReviewItem> queue = load(hospital, department, score, reason, page);
        populate(model, queue);

        model.addAttribute("scores", properties.queue().triageScoreOrder());
        model.addAttribute("reasons", UnmatchedReason.values());
        model.addAttribute("filterHospital", hospital);
        model.addAttribute("filterDepartment", department);
        model.addAttribute("filterScore", score);
        model.addAttribute("filterReason", reason);
        model.addAttribute("refreshSeconds", properties.queue().refreshInterval().toSeconds());
        model.addAttribute("quietAfterSeconds",
                properties.queue().refreshQuietAfter().toSeconds());
        model.addAttribute("oldestWaitingSince", items.oldestWaitingSince());

        // What a reviewer was shown is part of what they decided from, so the filter and the
        // ordering are recorded, not just the fact that they looked (FR-WL-10).
        audit.record(AuditEvent.WORKLIST_VIEWED, null, null, null,
                "Worklist opened, page " + page + "; " + describeFilters(hospital, department,
                        score, reason) + "; ordered by triage score then waiting time; "
                        + queue.getTotalElements() + " item(s) waiting");
        return "worklist";
    }

    /**
     * The list on its own, for the background refresh.
     *
     * @param seen the signature the client is displaying. When it still matches, this answers
     *             {@code 204} — no markup, no DOM change, no audit row (FR-WL-11a)
     */
    @GetMapping("/worklist/rows")
    @Transactional
    public String rows(@RequestParam(required = false) String hospital,
                       @RequestParam(required = false) String department,
                       @RequestParam(required = false) String score,
                       @RequestParam(required = false) UnmatchedReason reason,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) String seen,
                       HttpServletResponse response,
                       Model model) {

        Page<ReviewItem> queue = load(hospital, department, score, reason, page);
        String signature = QueueSignature.of(queue.getContent(), queue.getTotalElements());

        if (QueueSignature.unchanged(seen, signature)) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return NOTHING_TO_SEND;
        }

        populate(model, queue);

        // Audited only because it changed, and named as a refresh so an auditor can tell it from
        // a reviewer opening the page (FR-WL-11a, FR-WL-10).
        audit.record(AuditEvent.WORKLIST_VIEWED, null, null, null,
                "Worklist refreshed in the background, page " + page + "; "
                        + describeFilters(hospital, department, score, reason) + "; the list "
                        + "changed; " + queue.getTotalElements() + " item(s) waiting");
        return "fragments/worklist-rows :: rows";
    }

    private Page<ReviewItem> load(String hospital, String department, String score,
                                  UnmatchedReason reason, int page) {
        return items.queue(blankToNull(hospital), blankToNull(department), blankToNull(score),
                reason, PageRequest.of(Math.max(page, 0), PAGE_SIZE));
    }

    /** The model both the page and the fragment need. */
    private void populate(Model model, Page<ReviewItem> queue) {
        Instant now = Instant.now(clock);
        Map<Long, String> waited = new LinkedHashMap<>();
        queue.forEach(item -> waited.put(item.getId(),
                QueueOrdering.describe(QueueOrdering.waited(item.getWaitingSince(), now))));

        model.addAttribute("queue", queue);
        model.addAttribute("waited", waited);
        model.addAttribute("now", now);
        model.addAttribute("signature",
                QueueSignature.of(queue.getContent(), queue.getTotalElements()));
    }

    private static String describeFilters(String hospital, String department, String score,
                                          UnmatchedReason reason) {
        return "filters hospital=" + nullSafe(hospital)
                + " department=" + nullSafe(department)
                + " score=" + nullSafe(score)
                + " reason=" + (reason == null ? "" : reason);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
