package com.newbrid.epr_automation.pdsreview.auth;

import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

import java.io.Serializable;

/**
 * The one role this session is working under.
 *
 * <p>Session-scoped, and holding exactly one URID, because that is the shape of the rule: a
 * reviewer who holds both the review and audit activities exercises one at a time and re-selects
 * to switch (FR-AUTH-07a). A field that could hold several would make the rule a convention
 * rather than a constraint.
 */
@Component
@Scope(value = WebApplicationContext.SCOPE_SESSION, proxyMode = ScopedProxyMode.TARGET_CLASS)
public class RoleSelection implements Serializable {

    private String urid;

    public String urid() {
        return urid;
    }

    public boolean selected() {
        return urid != null && !urid.isBlank();
    }

    public void select(String urid) {
        this.urid = urid;
    }

    /** Cleared on sign-out, so a shared workstation does not inherit the last person's role. */
    public void clear() {
        this.urid = null;
    }
}
