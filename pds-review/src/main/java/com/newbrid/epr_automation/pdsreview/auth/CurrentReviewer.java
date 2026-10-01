package com.newbrid.epr_automation.pdsreview.auth;

import com.newbrid.epr_automation.pdsreview.config.PdsReviewProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Who is signed in, and what the role they picked lets them do.
 *
 * <p>Every capability question in the application comes through here, and every one of them is
 * answered from the <em>selected</em> role's activity codes rather than from a Spring Security
 * authority granted at login. That is deliberate: authorities are fixed when the session is
 * created, and this application's rule is that capability changes when the reviewer changes role
 * (FR-AUTH-07a).
 */
@Component
public class CurrentReviewer {

    private final NationalRbacRoles roles;
    private final RoleSelection selection;
    private final PdsReviewProperties properties;

    public CurrentReviewer(NationalRbacRoles roles, RoleSelection selection,
                           PdsReviewProperties properties) {
        this.roles = roles;
        this.selection = selection;
        this.properties = properties;
    }

    /** Every role the signed-in person holds, for the selection screen (FR-AUTH-04). */
    public List<NationalRbacRole> availableRoles() {
        return roles.rolesFor(authentication());
    }

    public boolean signedIn() {
        Authentication authentication = authentication();
        return authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal());
    }

    public boolean roleSelected() {
        return selection.selected() && current().isPresent();
    }

    /** Records the reviewer's choice. Rejects a URID they do not hold. */
    public boolean select(String urid) {
        boolean held = availableRoles().stream()
                .anyMatch(role -> role.urid().equals(urid));
        if (held) {
            selection.select(urid);
        }
        return held;
    }

    public void clearSelection() {
        selection.clear();
    }

    /** The signed-in reviewer under their selected role, if they have chosen one. */
    public Optional<ReviewerIdentity> current() {
        if (!selection.selected()) {
            return Optional.empty();
        }
        Authentication authentication = authentication();
        return availableRoles().stream()
                .filter(role -> role.urid().equals(selection.urid()))
                .findFirst()
                .map(role -> new ReviewerIdentity(
                        roles.userId(authentication),
                        roles.displayName(authentication),
                        role,
                        roles.bearerToken(authentication)));
    }

    /**
     * The reviewer, or a failure. Called from anywhere that is about to touch patient data, so
     * that "no role selected" cannot silently become an unattributed action — every audit entry
     * and every decision has to name a person and a role (FR-DEC-11).
     */
    public ReviewerIdentity require() {
        return current().orElseThrow(() -> new IllegalStateException(
                "No national RBAC role is selected for this session"));
    }

    /** Whether the selected role carries the activity that grants the queue (FR-AUTH-06). */
    public boolean canReview() {
        return current()
                .map(reviewer -> reviewer.canReview(properties.auth().activity().review()))
                .orElse(false);
    }

    /** Whether the selected role carries the activity that grants the audit trail. */
    public boolean canAudit() {
        return current()
                .map(reviewer -> reviewer.canAudit(properties.auth().activity().audit()))
                .orElse(false);
    }

    public String reviewActivity() {
        return properties.auth().activity().review();
    }

    public String auditActivity() {
        return properties.auth().activity().audit();
    }

    private static Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
