package com.newbrid.epr_automation.pdsreview.web;

import com.newbrid.epr_automation.pdsreview.SamplePresentation;
import com.newbrid.epr_automation.pdsreview.audit.AuditEvent;
import com.newbrid.epr_automation.pdsreview.audit.AuditRepository;
import com.newbrid.epr_automation.pdsreview.auth.CurrentReviewer;
import com.newbrid.epr_automation.pdsreview.auth.NationalRbacRole;
import com.newbrid.epr_automation.pdsreview.auth.ReviewerIdentity;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItemRepository;
import com.newbrid.epr_automation.pdsreview.intake.PresentationIntakeService;
import com.newbrid.epr_automation.pdsreview.review.ReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The worklist keeping itself current (FR-WL-09, FR-WL-11) — and, just as importantly, staying
 * quiet while it does (FR-WL-11a, FR-WL-11b).
 *
 * <p>The quiet half is what these tests guard hardest, because it is what makes the noisy half
 * safe to have. A refresh that audited every poll would bury the disclosures the trail exists to
 * answer for; a refresh that never stopped would hold a clinical session open all night.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorklistRefreshTest {

    private static final JsonMapper JSON = new JsonMapper();

    private static final NationalRbacRole ROLE = new NationalRbacRole("555021935107", "R8000",
            "Clinical Practitioner", "X26", "Test Trust", List.of("B0830"));
    private static final ReviewerIdentity ALICE =
            new ReviewerIdentity("cis2:alice", "Alice", ROLE, null);
    private static final ReviewerIdentity BOB =
            new ReviewerIdentity("cis2:bob", "Bob", ROLE, null);

    @Autowired MockMvc mvc;
    @Autowired PresentationIntakeService intake;
    @Autowired ReviewService reviews;
    @Autowired ReviewItemRepository items;
    @Autowired AuditRepository audit;

    @MockitoBean CurrentReviewer currentReviewer;

    @BeforeEach
    void signedIn() {
        items.deleteAll();
        audit.deleteAll();
        beReviewer(ALICE);
    }

    private void beReviewer(ReviewerIdentity reviewer) {
        when(currentReviewer.require()).thenReturn(reviewer);
        when(currentReviewer.current()).thenReturn(Optional.of(reviewer));
        when(currentReviewer.canReview()).thenReturn(true);
        when(currentReviewer.canAudit()).thenReturn(false);
        when(currentReviewer.roleSelected()).thenReturn(true);
        when(currentReviewer.signedIn()).thenReturn(true);
        when(currentReviewer.reviewActivity()).thenReturn("B0830");
        when(currentReviewer.auditActivity()).thenReturn("B0011");
    }

    // ---- the page carries what the refresh needs -------------------------------------

    @Test
    @WithMockUser
    void thePageTellsTheClientWhereToPollAndHowOften() throws Exception {
        String page = mvc.perform(get("/worklist"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page)
                .contains("id=\"worklist-rows\"")
                .contains("/worklist/rows")
                .contains("data-refresh-seconds=\"30\"")
                // The idle cut-off has to be well under the 15-minute session timeout, or the
                // poll would keep an unattended clinical session alive (FR-WL-11b).
                .contains("data-quiet-after-seconds=\"300\"")
                .contains("data-worklist-signature");
    }

    @Test
    @WithMockUser
    void theFilterFormAndTheScriptSitOutsideTheSwappedRegion() throws Exception {
        // A refresh must not take a reviewer's cursor out of a filter field mid-keystroke.
        String page = mvc.perform(get("/worklist")).andReturn().getResponse()
                .getContentAsString();

        int filters = page.indexOf("Filter this list");
        int region = page.indexOf("id=\"worklist-rows\"");
        int script = page.indexOf("worklist-refresh-live");

        assertThat(filters).isLessThan(region);
        assertThat(script).isGreaterThan(region);
    }

    // ---- unchanged: 204, no markup, no audit row (FR-WL-11a) ------------------------

    @Test
    @WithMockUser
    void anUnchangedListAnswers204AndWritesNothing() throws Exception {
        intake.admit(SamplePresentation.unmatched(), "gw-1");
        String signature = signatureOnScreen();
        long auditRowsBefore = audit.count();

        mvc.perform(get("/worklist/rows").param("seen", signature))
                .andExpect(status().isNoContent())
                .andExpect(content().string(org.hamcrest.Matchers.emptyOrNullString()));

        assertThat(audit.count())
                .as("a refresh that discloses nothing new is not a disclosure")
                .isEqualTo(auditRowsBefore);
    }

    @Test
    @WithMockUser
    void aPollWithNoSignatureIsAnsweredInFull() throws Exception {
        // First poll after a reconnect: the client has nothing to compare, so it gets the list.
        intake.admit(SamplePresentation.unmatched(), "gw-1");

        mvc.perform(get("/worklist/rows"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("waiting")));
    }

    // ---- changed: the fragment, and one audit row -----------------------------------

    @Test
    @WithMockUser
    void anArrivalReachesTheOpenWorklistWithoutAnyoneReloading() throws Exception {
        String emptySignature = signatureOnScreen();

        // The gateway admits a patient while the reviewer is just looking at the list.
        intake.admit(SamplePresentation.unmatched(), "gw-1");

        mvc.perform(get("/worklist/rows").param("seen", emptySignature))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ava")))
                .andExpect(content().string(containsString("1</span> waiting")))
                // The fragment only — no page furniture to splice into the middle of a document.
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<html"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("Filter this list"))));

        assertThat(audit.findAll())
                .filteredOn(entry -> entry.getEvent() == AuditEvent.WORKLIST_VIEWED)
                .anySatisfy(entry -> assertThat(entry.getDetail())
                        .as("distinguishable from a reviewer opening the page (FR-WL-10)")
                        .contains("refreshed in the background")
                        .contains("the list changed"));
    }

    @Test
    @WithMockUser
    void anItemAnotherReviewerTakesTurnsIntoBeingReviewedRatherThanVanishing() throws Exception {
        // FR-WL-06, and the reason the former FR-WL-12 was withdrawn: the row stays, marked, so a
        // reviewer can see an urgent patient is being handled instead of wondering where they went.
        intake.admit(SamplePresentation.unmatched(), "gw-1");
        String before = signatureOnScreen();

        beReviewer(BOB);
        reviews.claim(items.findAll().getFirst().getId());
        beReviewer(ALICE);

        String fragment = mvc.perform(get("/worklist/rows").param("seen", before))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(fragment)
                .as("the signature covers state, so taking an item is a visible change")
                .contains("being reviewed")
                .contains("Ava");
        assertThat(fragment)
                .as("and it is no longer openable")
                .doesNotContain("/review/" + items.findAll().getFirst().getId() + "\"");
    }

    @Test
    @WithMockUser
    void aDecidedItemLeavesTheOpenWorklist() throws Exception {
        intake.admit(SamplePresentation.unmatched(), "gw-1");
        String withOneItem = signatureOnScreen();

        Long id = items.findAll().getFirst().getId();
        reviews.claim(id);
        var item = items.findById(id).orElseThrow();
        item.decide(com.newbrid.epr_automation.pdsreview.domain.Decision.CLOSED_NO_LONGER_REQUIRED,
                ALICE.userId(), ROLE.urid(), java.time.Instant.now(), "Patient left", null,
                null, null);
        items.saveAndFlush(item);

        mvc.perform(get("/worklist/rows").param("seen", withOneItem))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nothing is waiting for review")));
    }

    // ---- the refresh is still subject to every other rule ---------------------------

    @Test
    @WithMockUser
    void theRefreshIsRefusedToARoleThatCannotReview() throws Exception {
        // The capability check covers every path, so a background poll cannot be a way around it.
        when(currentReviewer.canReview()).thenReturn(false);
        when(currentReviewer.canAudit()).thenReturn(true);

        mvc.perform(get("/worklist/rows"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void theRefreshNeedsASignedInReviewer() throws Exception {
        mvc.perform(get("/worklist/rows"))
                .andExpect(status().is3xxRedirection());
    }

    /** The signature the browser would currently be holding, read out of the rendered page. */
    private String signatureOnScreen() throws Exception {
        String fragment = mvc.perform(get("/worklist/rows"))
                .andReturn().getResponse().getContentAsString();
        int at = fragment.indexOf("data-worklist-signature=\"");
        assertThat(at).as("the fragment must carry its signature").isNotNegative();
        int from = at + "data-worklist-signature=\"".length();
        return fragment.substring(from, fragment.indexOf('"', from));
    }
}
