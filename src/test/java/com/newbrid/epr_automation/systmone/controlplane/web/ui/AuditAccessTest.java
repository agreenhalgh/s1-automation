package com.newbrid.epr_automation.systmone.controlplane.web.ui;

import com.newbrid.epr_automation.systmone.controlplane.service.AuditService;
import com.newbrid.epr_automation.systmone.controlplane.service.DispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit trail belongs to AUDITOR and to nobody else, and dispatching a run belongs to
 * everybody else and not to AUDITOR (NFR-SEC-01, NFR-SEC-05). Both halves are asserted here
 * because they are one decision: the role that reviews what the automation did does not also
 * get to make it do things.
 *
 * <p>Every POST carries a CSRF token. Without one the UI chain rejects the request with 403
 * regardless of role, which would make the "auditor cannot dispatch" assertions pass for
 * entirely the wrong reason — and keep passing if the role rule were deleted.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditAccessTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AuditService audit;

    /**
     * Mocked so the assertions can be about authorisation rather than about whether a bot
     * happens to be registered — and, for the auditor, so "was a run dispatched?" is a
     * question that can actually be asked. A 403 says the request was refused; {@code never()}
     * says nothing happened, which is the requirement.
     */
    @MockitoBean
    private DispatchService dispatch;

    // ---- the viewer is AUDITOR-only -----------------------------------------------------

    @Test
    @WithMockUser(username = "auditor", roles = "AUDITOR")
    void auditorCanOpenTheTrail() throws Exception {
        audit.record("operator", "DISPATCH", "bot-a", "run-1", "capabilityTag=null");

        mvc.perform(get("/audit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Audit trail")))
                .andExpect(content().string(containsString("DISPATCH")));
    }

    @Test
    @WithMockUser(username = "operator", roles = "OPERATOR")
    void operatorIsRefusedTheTrail() throws Exception {
        mvc.perform(get("/audit")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "developer", roles = "DEVELOPER")
    void developerIsRefusedTheTrail() throws Exception {
        mvc.perform(get("/audit")).andExpect(status().isForbidden());
    }

    /**
     * ADMIN is refused too, and that is the intended behaviour rather than an oversight: whether
     * an administrator may read the record of what the automation did to patient records is an
     * information-governance decision. If it is later decided that they may, this test is the
     * thing that has to change, deliberately.
     */
    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminDoesNotInheritTheTrail() throws Exception {
        mvc.perform(get("/audit")).andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsSentToLogin() throws Exception {
        mvc.perform(get("/audit"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .as("unauthenticated access is a login prompt, not a 403")
                        .contains("/login"));
    }

    // ---- dispatching is closed to AUDITOR ----------------------------------------------

    @Test
    @WithMockUser(username = "auditor", roles = "AUDITOR")
    void auditorCannotDispatchARun() throws Exception {
        mvc.perform(post("/ui/dispatch").with(csrf()).param("botId", "bot-a"))
                .andExpect(status().isForbidden());

        verify(dispatch, never()).dispatch(anyString(), any(), any(), anyString());
    }

    /**
     * A user holding AUDITOR <em>and</em> OPERATOR may still dispatch. The rule is "dispatching
     * needs a dispatching role", not "having AUDITOR disqualifies you" — the difference matters
     * for a real deployment where SSO claims can grant both.
     */
    @Test
    @WithMockUser(username = "both", roles = {"AUDITOR", "OPERATOR"})
    void holdingAuditorAlongsideOperatorStillDispatches() throws Exception {
        when(dispatch.dispatch(anyString(), any(), any(), anyString())).thenReturn("run-x");

        mvc.perform(post("/ui/dispatch").with(csrf()).param("botId", "bot-a"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/runs/run-x"));
    }

    @Test
    @WithMockUser(username = "operator", roles = "OPERATOR")
    void operatorCanDispatch() throws Exception {
        when(dispatch.dispatch(anyString(), any(), any(), anyString())).thenReturn("run-y");

        mvc.perform(post("/ui/dispatch").with(csrf()).param("botId", "bot-a"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/runs/run-y"));

        // The trail records who asked for it, not just that it happened (NFR-SEC-05).
        verify(dispatch).dispatch("bot-a", java.util.Map.of(), null, "operator");
    }

    // ---- the dashboard offers only what the role can do --------------------------------

    @Test
    @WithMockUser(username = "auditor", roles = "AUDITOR")
    void dashboardHidesDispatchFromAnAuditorAndOffersTheTrail() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(
                        containsString("Dispatch a run"))))
                .andExpect(content().string(containsString("/audit")));
    }

    @Test
    @WithMockUser(username = "operator", roles = "OPERATOR")
    void dashboardOffersDispatchToAnOperatorAndHidesTheTrail() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Dispatch a run")))
                .andExpect(content().string(not(
                        containsString("/audit"))));
    }
}
