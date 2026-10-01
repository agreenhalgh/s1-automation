package com.newbrid.epr_automation.pdsreview.web;

import com.newbrid.epr_automation.pdsreview.SamplePresentation;
import com.newbrid.epr_automation.pdsreview.auth.CurrentReviewer;
import com.newbrid.epr_automation.pdsreview.auth.NationalRbacRole;
import com.newbrid.epr_automation.pdsreview.auth.ReviewerIdentity;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItemRepository;
import com.newbrid.epr_automation.pdsreview.intake.PresentationIntakeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders every page.
 *
 * <p>Templates are the one part of this application the compiler cannot check. A mistyped
 * expression, a fragment that does not exist, a record accessor called as a property — all of it
 * passes the build and fails in front of a clinician. These tests exist to move that failure to
 * where it belongs.
 *
 * <p>They also pin the handful of things a reviewer must not be able to miss: that an unscored
 * item is visibly unscored, that a PDS error does not read as "no matches", and that the two
 * classes of decision are presented as the different things they are.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PageRenderingTest {

    private static final NationalRbacRole ROLE = new NationalRbacRole("555021935107", "R8000",
            "Clinical Practitioner", "X26", "Test Trust", List.of("B0830"));
    private static final ReviewerIdentity ALICE =
            new ReviewerIdentity("cis2:alice", "Alice", ROLE, null);

    @Autowired MockMvc mvc;
    @Autowired PresentationIntakeService intake;
    @Autowired ReviewItemRepository items;

    @MockitoBean CurrentReviewer currentReviewer;

    @BeforeEach
    void signedInAndAbleToReview() {
        items.deleteAll();
        when(currentReviewer.require()).thenReturn(ALICE);
        when(currentReviewer.current()).thenReturn(Optional.of(ALICE));
        when(currentReviewer.availableRoles()).thenReturn(List.of(ROLE));
        when(currentReviewer.canReview()).thenReturn(true);
        when(currentReviewer.canAudit()).thenReturn(false);
        when(currentReviewer.roleSelected()).thenReturn(true);
        when(currentReviewer.signedIn()).thenReturn(true);
        when(currentReviewer.reviewActivity()).thenReturn("B0830");
        when(currentReviewer.auditActivity()).thenReturn("B0011");
    }

    @Test
    @WithMockUser
    void rendersTheEmptyWorklist() throws Exception {
        mvc.perform(get("/worklist"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Nothing is waiting for review")))
                // The NHS.UK frontend's own stylesheet, served by this application (FR-UI-03).
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/stylesheets/application.min.css")));
    }

    @Test
    @WithMockUser
    void aPatientWithNoTriageScoreIsVisiblyUnscored() throws Exception {
        intake.admit(SamplePresentation.unmatched(), "gw-1");
        items.findAll().forEach(item -> {
            // The captured payload is a P5; blank the score to exercise the band that matters.
            org.springframework.test.util.ReflectionTestUtils.setField(item, "triageScore", null);
            items.save(item);
        });

        mvc.perform(get("/worklist"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("no score")));
    }

    @Test
    @WithMockUser
    void rendersTheReviewScreenAndDoesNotCallAPdsOutageNoMatches() throws Exception {
        intake.admit(SamplePresentation.unmatched(), "gw-1");
        Long id = items.findAll().getFirst().getId();

        mvc.perform(get("/review/" + id))
                .andExpect(status().isOk())
                // The patient's own answers, labelled as unverified (FR-REV-03).
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Patient-stated and unverified")))
                // Why they are in the queue at all (§2.2).
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Why this patient needs review")))
                // No PDS is configured in a test, so the search errors — and the screen must say
                // that rather than imply the patient is not on PDS (FR-PDS-12).
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "This is not the same as")))
                // Both classes of decision, presented as different things (FR-DEC-01a).
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "This will register a patient")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "This will close the item and register nobody")));
    }

    @Test
    @WithMockUser
    void rendersTheRoleChooser() throws Exception {
        when(currentReviewer.roleSelected()).thenReturn(false);

        mvc.perform(get("/roles"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Choose the role you are working under")));
    }

    @Test
    @WithMockUser
    void refusesTheAuditTrailToAReviewerAndExplainsWhy() throws Exception {
        // FR-AUTH-06 and FR-AUTH-07a: a role carrying B0830 does not get B0011's page, and is
        // told which activity is missing rather than shown a bare 403.
        String refusal = mvc.perform(get("/audit"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        org.assertj.core.api.Assertions.assertThat(refusal).startsWith("/refused?need=B0011");

        mvc.perform(get(refusal))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "does not carry activity")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("B0011")));
    }

    @Test
    @WithMockUser
    void rendersTheAuditTrailForARoleThatCarriesTheActivity() throws Exception {
        when(currentReviewer.canReview()).thenReturn(false);
        when(currentReviewer.canAudit()).thenReturn(true);

        mvc.perform(get("/audit"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Audit trail")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Your reading of it is recorded too")));
    }

    @Test
    @WithMockUser
    void sendsAReviewerWithNoRoleSelectedToChooseOne() throws Exception {
        when(currentReviewer.roleSelected()).thenReturn(false);

        mvc.perform(get("/worklist"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void anIntakePostWithoutTheTokenIsRefused() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/internal/v1/presentations")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(SamplePresentation.unmatched().toString()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void anIntakePostWithTheTokenIsAdmittedOnceAndOnlyOnce() throws Exception {
        String body = SamplePresentation.unmatched().toString();

        mvc.perform(post(body).header("X-Gateway-Token", "test-token"))
                .andExpect(status().isCreated())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ADMITTED")));

        // The gateway retries from its own store, so a redelivery has to be a distinguishable
        // answer rather than a second patient (FR-INT-02b, FR-INT-05).
        mvc.perform(post(body).header("X-Gateway-Token", "test-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ALREADY_HELD")));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            post(String body) throws IOException {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/internal/v1/presentations")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body);
    }
}
