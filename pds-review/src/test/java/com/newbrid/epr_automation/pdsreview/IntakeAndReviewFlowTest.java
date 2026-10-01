package com.newbrid.epr_automation.pdsreview;

import com.newbrid.epr_automation.pdsreview.audit.AuditEvent;
import com.newbrid.epr_automation.pdsreview.audit.AuditRepository;
import com.newbrid.epr_automation.pdsreview.auth.CurrentReviewer;
import com.newbrid.epr_automation.pdsreview.auth.NationalRbacRole;
import com.newbrid.epr_automation.pdsreview.auth.ReviewerIdentity;
import com.newbrid.epr_automation.pdsreview.decision.DecisionRequest;
import com.newbrid.epr_automation.pdsreview.decision.DecisionService;
import com.newbrid.epr_automation.pdsreview.domain.Decision;
import com.newbrid.epr_automation.pdsreview.domain.PostBackState;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItem;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItemRepository;
import com.newbrid.epr_automation.pdsreview.domain.ReviewState;
import com.newbrid.epr_automation.pdsreview.domain.UnmatchedReason;
import com.newbrid.epr_automation.pdsreview.intake.PresentationIntakeService;
import com.newbrid.epr_automation.pdsreview.review.ReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The path a patient takes through this application, against a real database.
 *
 * <p>Everything that could hide a defect in a unit test is real here: the unique constraint that
 * makes admission idempotent, the pessimistic lock that stops two reviewers holding one item, the
 * transaction that makes a decision and its audit entry inseparable, and the outbox row that a
 * decision leaves behind.
 *
 * <p>The reviewer is the one thing stubbed, because CIS2 is not available to a test — and
 * {@link CurrentReviewer} is the seam the requirements already put there for exactly that reason.
 */
@SpringBootTest
class IntakeAndReviewFlowTest {

    private static final JsonMapper JSON = new JsonMapper();

    private static final NationalRbacRole ROLE = new NationalRbacRole("555021935107", "R8000",
            "Clinical Practitioner", "X26", "Test Trust", List.of("B0830"));
    private static final ReviewerIdentity ALICE =
            new ReviewerIdentity("cis2:alice", "Alice", ROLE, null);
    private static final ReviewerIdentity BOB =
            new ReviewerIdentity("cis2:bob", "Bob", ROLE, null);

    @Autowired PresentationIntakeService intake;
    @Autowired ReviewService reviews;
    @Autowired DecisionService decisions;
    @Autowired ReviewItemRepository items;
    @Autowired AuditRepository audit;

    @MockitoBean CurrentReviewer currentReviewer;

    @BeforeEach
    void signInAsAlice() {
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

    // ---- intake ----------------------------------------------------------------------

    @Test
    void admitsACapturedUnmatchedPresentation() throws IOException {
        PresentationIntakeService.Result result = intake.admit(captured(), "gw-1");

        assertThat(result.outcome()).isEqualTo(PresentationIntakeService.Outcome.ADMITTED);

        ReviewItem item = items.findByItemKey(result.itemKey()).orElseThrow();
        assertThat(item.getState()).isEqualTo(ReviewState.WAITING);
        assertThat(item.getTriageScore()).isEqualTo("P5");
        assertThat(item.getUnmatchedReason()).isEqualTo(UnmatchedReason.NOT_STATED);
        assertThat(item.getStatedName()).contains("Ava");
        assertThat(item.getPresentationJson())
                .as("stored as it arrived, so the post-back can hand it on unaltered")
                .contains("\"externalValue\"");
        assertThat(audit.findAll())
                .extracting(entry -> entry.getEvent())
                .contains(AuditEvent.PRESENTATION_ADMITTED);
    }

    @Test
    void aRedeliveredPresentationDoesNotCreateASecondPatient() throws IOException {
        intake.admit(captured(), "gw-1");
        PresentationIntakeService.Result again = intake.admit(captured(), "gw-1");

        assertThat(again.outcome()).isEqualTo(PresentationIntakeService.Outcome.ALREADY_HELD);
        assertThat(items.count())
                .as("the gateway retries from its own store, so redelivery is the normal case")
                .isEqualTo(1);
    }

    @Test
    void refusesAPresentationThatIsAlreadyMatched() {
        JsonNode matched = JSON.readTree("""
                {"externalValue":{
                   "nhsUser":{"id":"9434765919"},
                   "user":{"_id":"session-1","nhs":{"status":"successful"}}}}
                """);

        assertThat(intake.admit(matched, "gw-2").outcome())
                .isEqualTo(PresentationIntakeService.Outcome.REJECTED);
        assertThat(items.count()).isZero();
    }

    @Test
    void refusesAPresentationWithNoStableIdentity() {
        // Without user._id there is nothing to key idempotency on, so a redelivery would
        // duplicate a patient in the queue.
        JsonNode anonymous = JSON.readTree("""
                {"externalValue":{"nhsUser":null,"user":{}}}
                """);

        assertThat(intake.admit(anonymous, "gw-3").outcome())
                .isEqualTo(PresentationIntakeService.Outcome.REJECTED);
    }

    // ---- the queue -------------------------------------------------------------------

    @Test
    void ordersTheQueueByUrgencyThenByWaitingTime() throws IOException {
        admitWith("s-p5", "P5", "2026-06-24T09:00:00Z");
        admitWith("s-p1", "P1", "2026-06-24T10:00:00Z");
        admitWith("s-none", null, "2026-06-24T10:30:00Z");
        admitWith("s-p1-older", "P1", "2026-06-24T08:00:00Z");

        List<ReviewItem> queue = items.queue(null, null, null, null, PageRequest.of(0, 10))
                .getContent();

        assertThat(queue).extracting(ReviewItem::getItemKey)
                .as("unscored first, then P1 oldest-first, then P5")
                .containsExactly("s-none", "s-p1-older", "s-p1", "s-p5");
    }

    // ---- locking ---------------------------------------------------------------------

    @Test
    void twoReviewersCannotHoldTheSameItem() throws IOException {
        Long id = admitAndFind();

        assertThat(reviews.claim(id).outcome()).isEqualTo(ReviewService.ClaimOutcome.CLAIMED);

        beReviewer(BOB);
        ReviewService.ClaimResult bobsAttempt = reviews.claim(id);

        assertThat(bobsAttempt.outcome()).isEqualTo(ReviewService.ClaimOutcome.ALREADY_HELD);
        assertThat(bobsAttempt.heldBy()).isEqualTo(ALICE.userId());
    }

    @Test
    void reclaimingYourOwnItemJustExtendsYourHold() throws IOException {
        Long id = admitAndFind();
        reviews.claim(id);

        assertThat(reviews.claim(id).outcome())
                .as("a reviewer who reloads the page has not lost their place")
                .isEqualTo(ReviewService.ClaimOutcome.CLAIMED);
    }

    @Test
    void returningToTheQueueKeepsThePriorityAndRecordsWhy() throws IOException {
        Long id = admitAndFind();
        reviews.claim(id);
        int rankBefore = items.findById(id).orElseThrow().getPriorityRank();

        reviews.returnToQueue(id, "Waiting for the patient to come back to the desk");

        ReviewItem item = items.findById(id).orElseThrow();
        assertThat(item.getState()).isEqualTo(ReviewState.WAITING);
        assertThat(item.getLockedBy()).isNull();
        assertThat(item.getPriorityRank())
                .as("urgency is not reduced by having been looked at")
                .isEqualTo(rankBefore);
        assertThat(audit.findAll()).extracting(entry -> entry.getEvent())
                .contains(AuditEvent.ITEM_RETURNED_TO_QUEUE);
    }

    @Test
    void anExpiredLockPutsThePatientBackInTheQueue() throws IOException {
        Long id = admitAndFind();
        reviews.claim(id);

        // Reach past the service to force expiry, because the alternative is a test that waits
        // twenty minutes.
        ReviewItem held = items.findById(id).orElseThrow();
        held.heldBy(ALICE.userId(), ROLE.urid(), java.time.Instant.now().minusSeconds(60));
        items.saveAndFlush(held);

        assertThat(reviews.reclaimExpiredLocks()).isEqualTo(1);
        assertThat(items.findById(id).orElseThrow().getState()).isEqualTo(ReviewState.WAITING);
        assertThat(audit.findAll()).extracting(entry -> entry.getEvent())
                .contains(AuditEvent.LOCK_EXPIRED);
    }

    // ---- decisions -------------------------------------------------------------------

    @Test
    void aClosureRegistersNobodyAndStillQueuesForDelivery() throws IOException {
        Long id = admitAndFind();
        reviews.claim(id);

        DecisionService.Result result = decisions.record(id, new DecisionRequest(
                Decision.CLOSED_REGISTERED_MANUALLY,
                "Registered by the ward clerk while the patient waited", "MRN-4471", null, true));

        assertThat(result.recorded()).isTrue();

        ReviewItem item = items.findById(id).orElseThrow();
        assertThat(item.getState()).isEqualTo(ReviewState.DECIDED);
        assertThat(item.getDecision().registers()).isFalse();
        assertThat(item.getExistingRecordId()).isEqualTo("MRN-4471");
        assertThat(item.getMatchedNhsNumber()).isNull();
        assertThat(item.getPostBackState())
                .as("a closure is still reported, so the gateway does not think it unresolved")
                .isEqualTo(PostBackState.PENDING);
    }

    @Test
    void aClosureWithoutAReasonIsRefusedBeforeAnythingIsWritten() throws IOException {
        Long id = admitAndFind();
        reviews.claim(id);

        DecisionService.Result result = decisions.record(id, new DecisionRequest(
                Decision.CLOSED_NO_LONGER_REQUIRED, null, null, null, true));

        assertThat(result.recorded()).isFalse();
        assertThat(items.findById(id).orElseThrow().getState()).isEqualTo(ReviewState.IN_REVIEW);
    }

    @Test
    void anAcceptedUnmatchedDecisionRegistersAndLeavesTheQueue() throws IOException {
        Long id = admitAndFind();
        reviews.claim(id);

        assertThat(decisions.record(id, new DecisionRequest(Decision.ACCEPTED_NOT_ON_PDS,
                "PDS searched three ways, no match", null, null, true)).recorded()).isTrue();

        ReviewItem item = items.findById(id).orElseThrow();
        assertThat(item.getDecision().registers()).isTrue();
        assertThat(items.queue(null, null, null, null, PageRequest.of(0, 10)))
                .as("a decided item is off the worklist")
                .isEmpty();
    }

    @Test
    void aDecisionIsNotReMade() throws IOException {
        Long id = admitAndFind();
        reviews.claim(id);
        decisions.record(id, new DecisionRequest(Decision.CLOSED_NO_LONGER_REQUIRED,
                "Patient left", null, null, true));

        DecisionService.Result second = decisions.record(id, new DecisionRequest(
                Decision.ACCEPTED_NOT_ON_PDS, "changed my mind", null, null, true));

        assertThat(second.recorded()).isFalse();
        assertThat(second.problems()).singleElement().asString().contains("already been decided");
        assertThat(items.findById(id).orElseThrow().getDecision())
                .isEqualTo(Decision.CLOSED_NO_LONGER_REQUIRED);
    }

    @Test
    void aMatchIsRefusedWhenPdsCannotBeReached() throws IOException {
        // No PDS is configured in a test, so the candidate cannot be read back — and a match
        // nobody can verify must not be recorded.
        Long id = admitAndFind();
        reviews.claim(id);

        DecisionService.Result result = decisions.record(id, new DecisionRequest(
                Decision.MATCHED, null, null, "9434765919", true));

        assertThat(result.recorded()).isFalse();
        assertThat(result.problems()).singleElement().asString().contains("could not be read back");
        assertThat(items.findById(id).orElseThrow().getState()).isEqualTo(ReviewState.IN_REVIEW);
    }

    @Test
    void everyDecisionIsAttributedToAPersonAndARole() throws IOException {
        Long id = admitAndFind();
        reviews.claim(id);
        decisions.record(id, new DecisionRequest(Decision.CLOSED_NO_LONGER_REQUIRED,
                "Seen elsewhere", null, null, true));

        ReviewItem item = items.findById(id).orElseThrow();
        assertThat(item.getDecidedBy()).isEqualTo(ALICE.userId());
        assertThat(item.getDecidedByRole()).isEqualTo(ROLE.urid());

        assertThat(audit.findAll())
                .filteredOn(entry -> entry.getEvent() == AuditEvent.DECISION_RECORDED)
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getActor()).isEqualTo(ALICE.userId());
                    assertThat(entry.getActorRole()).isEqualTo(ROLE.urid());
                    assertThat(entry.getDetail())
                            .as("one line has to say whether a patient was registered")
                            .contains("no patient will be registered");
                });
    }

    // ---- helpers ---------------------------------------------------------------------

    private Long admitAndFind() throws IOException {
        PresentationIntakeService.Result result = intake.admit(captured(), "gw-1");
        return items.findByItemKey(result.itemKey()).orElseThrow().getId();
    }

    private void admitWith(String sessionId, String triageScore, String triageEnd) {
        String json = """
                {"externalValue":{"nhsUser":null,"user":{
                   "_id":"%s","endTimestamp":"%s","data":[],
                   %s"hospitalId":{"name":"Test Hospital"}}}}
                """.formatted(sessionId, triageEnd,
                triageScore == null ? "" : "\"pValue\":\"" + triageScore + "\",");
        assertThat(intake.admit(JSON.readTree(json), "gw-" + sessionId).outcome())
                .isEqualTo(PresentationIntakeService.Outcome.ADMITTED);
    }

    private static JsonNode captured() throws IOException {
        return SamplePresentation.unmatched();
    }
}
