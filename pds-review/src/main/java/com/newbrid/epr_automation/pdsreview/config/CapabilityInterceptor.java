package com.newbrid.epr_automation.pdsreview.config;

import com.newbrid.epr_automation.pdsreview.audit.AuditEvent;
import com.newbrid.epr_automation.pdsreview.audit.AuditService;
import com.newbrid.epr_automation.pdsreview.auth.CurrentReviewer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Enforces, on every request that touches patient data, that a role is selected and that it
 * carries the right activity.
 *
 * <p>An interceptor rather than a security rule because of what FR-AUTH-06 asks for: not a bare
 * 403, but a plain account of which activity is missing and who can grant it. A reviewer whose
 * Registration Authority has not given them `B0830` needs to be told that, not shown an error
 * page — and certainly not admitted and left to hit a 403 from PDS on their first search.
 *
 * <p>It also enforces the rule that makes the separation of duties real: capability comes from
 * the <em>selected</em> role, never the union of the roles a person holds (FR-AUTH-07a). Someone
 * with both activities is refused the audit trail while working the queue, and refused the queue
 * while reading the trail, until they go back and re-select.
 */
@Component
public class CapabilityInterceptor implements HandlerInterceptor {

    private final CurrentReviewer currentReviewer;
    private final AuditService audit;

    public CapabilityInterceptor(CurrentReviewer currentReviewer, AuditService audit) {
        this.currentReviewer = currentReviewer;
        this.audit = audit;
    }

    @Override
    @Transactional
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String path = request.getRequestURI();

        if (!currentReviewer.signedIn() || open(path)) {
            return true;
        }

        if (!currentReviewer.roleSelected()) {
            // Not a failure — most reviewers land here on their first request of the day.
            response.sendRedirect(request.getContextPath() + "/roles");
            return false;
        }

        boolean auditPath = path.startsWith("/audit");
        boolean allowed = auditPath ? currentReviewer.canAudit() : currentReviewer.canReview();
        if (allowed) {
            return true;
        }

        String needed = auditPath ? currentReviewer.auditActivity() : currentReviewer.reviewActivity();
        audit.record(AuditEvent.ACCESS_REFUSED, null, null, null,
                "Refused " + path + ": the selected role does not carry " + needed);

        // A redirect rather than a forward, and carrying only the activity code — which comes
        // from configuration, not from the request. The refused page never echoes back a path the
        // caller supplied.
        response.sendRedirect(request.getContextPath() + "/refused?need="
                + URLEncoder.encode(needed, StandardCharsets.UTF_8));
        return false;
    }

    /** Paths that a signed-in reviewer reaches before, or instead of, selecting a role. */
    private static boolean open(String path) {
        return path.startsWith("/roles")
                || path.startsWith("/refused")
                || path.startsWith("/logout")
                || path.startsWith("/error")
                || path.startsWith("/actuator")
                || path.startsWith("/stylesheets")
                || path.startsWith("/javascripts")
                || path.startsWith("/assets");
    }
}
