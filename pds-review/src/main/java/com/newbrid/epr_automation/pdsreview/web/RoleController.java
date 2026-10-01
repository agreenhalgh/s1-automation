package com.newbrid.epr_automation.pdsreview.web;

import com.newbrid.epr_automation.pdsreview.audit.AuditEvent;
import com.newbrid.epr_automation.pdsreview.audit.AuditService;
import com.newbrid.epr_automation.pdsreview.auth.CurrentReviewer;
import com.newbrid.epr_automation.pdsreview.auth.NationalRbacRole;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Choosing the national RBAC role to work under (FR-AUTH-04).
 *
 * <p>Not a formality. The URID of the role chosen here goes to PDS on every search
 * (FR-AUTH-05), it appears on every audit entry, and it alone decides what this session can do —
 * somebody holding both `B0830` and `B0011` exercises one at a time and comes back here to switch
 * (FR-AUTH-07a).
 */
@Controller
public class RoleController {

    private final CurrentReviewer currentReviewer;
    private final AuditService audit;

    public RoleController(CurrentReviewer currentReviewer, AuditService audit) {
        this.currentReviewer = currentReviewer;
        this.audit = audit;
    }

    /** Straight to the queue once a role is chosen; to the chooser if not. */
    @GetMapping("/")
    public String home() {
        return currentReviewer.roleSelected() ? "redirect:/worklist" : "redirect:/roles";
    }

    @GetMapping("/roles")
    public String chooseRole(Model model) {
        List<NationalRbacRole> roles = currentReviewer.availableRoles();
        model.addAttribute("roles", roles);
        model.addAttribute("reviewActivity", currentReviewer.reviewActivity());
        model.addAttribute("auditActivity", currentReviewer.auditActivity());
        // A person with no roles at all is a real situation — nothing granted yet — and saying so
        // is more use than an empty list.
        model.addAttribute("noRoles", roles.isEmpty());
        return "roles";
    }

    @PostMapping("/roles")
    @Transactional
    public String selectRole(@RequestParam("urid") String urid, Model model) {
        if (!currentReviewer.select(urid)) {
            model.addAttribute("roles", currentReviewer.availableRoles());
            model.addAttribute("problem", "That role is not one you hold");
            return "roles";
        }
        audit.record(AuditEvent.ROLE_SELECTED, null, null, null,
                "Working under role " + urid);
        return currentReviewer.canAudit() && !currentReviewer.canReview()
                ? "redirect:/audit"
                : "redirect:/worklist";
    }

    /**
     * Shown when the selected role does not carry the activity a page needs (FR-AUTH-06).
     *
     * @param need the activity code that was missing. Comes from configuration by way of
     *             {@code CapabilityInterceptor}, never from anything the caller composed
     */
    @GetMapping("/refused")
    public String refused(@RequestParam(name = "need", required = false) String need,
                          Model model) {
        model.addAttribute("missingActivity", need == null ? currentReviewer.reviewActivity() : need);
        model.addAttribute("roles", currentReviewer.availableRoles());
        return "refused";
    }
}
