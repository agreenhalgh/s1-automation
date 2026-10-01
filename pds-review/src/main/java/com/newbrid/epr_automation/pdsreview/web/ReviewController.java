package com.newbrid.epr_automation.pdsreview.web;

import com.newbrid.epr_automation.pdsreview.decision.DecisionRequest;
import com.newbrid.epr_automation.pdsreview.decision.DecisionService;
import com.newbrid.epr_automation.pdsreview.domain.Decision;
import com.newbrid.epr_automation.pdsreview.domain.ReviewItem;
import com.newbrid.epr_automation.pdsreview.ods.OdsClient;
import com.newbrid.epr_automation.pdsreview.pds.PdsSearchResult;
import com.newbrid.epr_automation.pdsreview.pds.PostcodeNormaliser;
import com.newbrid.epr_automation.pdsreview.review.PatientSearchService;
import com.newbrid.epr_automation.pdsreview.review.ReviewService;
import com.newbrid.epr_automation.pdsreview.review.ReviewView;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Base64;
import java.util.List;

/**
 * The review screen and everything a reviewer can do on it.
 *
 * <p><b>Nothing here enforces an order between the three searches</b> (FR-REV-09). Each is its own
 * endpoint, reachable at any point, and a reviewer who can already see that the patient's name was
 * transposed can go straight to the fuzzy search without walking two others they know will return
 * nothing. What replaces the enforcement is the record: every execution is audited in sequence,
 * and the decision carries them (FR-REV-10).
 *
 * <p>The automatic non-fuzzy search runs on each GET of the page rather than once per session.
 * That costs a PDS call on a reload, but it means the candidate list is never a stale snapshot,
 * and each disclosure is separately audited — which is what FR-PDS-10 asks for anyway.
 */
@Controller
public class ReviewController {

    private final ReviewService reviews;
    private final PatientSearchService searches;
    private final DecisionService decisions;

    public ReviewController(ReviewService reviews, PatientSearchService searches,
                            DecisionService decisions) {
        this.reviews = reviews;
        this.searches = searches;
        this.decisions = decisions;
    }

    @GetMapping("/review/{id}")
    public String open(@PathVariable Long id, Model model, RedirectAttributes redirect) {
        ReviewService.ClaimResult claim = reviews.claim(id);

        switch (claim.outcome()) {
            case NOT_FOUND -> {
                redirect.addFlashAttribute("problem", "That review item no longer exists");
                return "redirect:/worklist";
            }
            case ALREADY_DECIDED -> {
                redirect.addFlashAttribute("problem",
                        "That item has already been decided (" + claim.item().getDecision() + ")");
                return "redirect:/worklist";
            }
            case ALREADY_HELD -> {
                redirect.addFlashAttribute("problem",
                        "Another reviewer is holding that item. Two people matching the same "
                                + "patient is how one patient becomes two records.");
                return "redirect:/worklist";
            }
            default -> { /* claimed */ }
        }

        ReviewItem item = claim.item();
        ReviewView view = reviews.open(item);
        model.addAttribute("view", view);
        model.addAttribute("decisions", Decision.values());

        // The automatic first search: non-fuzzy, from the patient's own answers, with history
        // (FR-PDS-01, FR-PDS-02). Not attempted at all when a required field is missing — and
        // that is shown as a named list, not as an empty candidate table (FR-PDS-04).
        PdsSearchResult nonFuzzy = searches.nonFuzzy(item, view.derived());
        model.addAttribute("nonFuzzy", nonFuzzy);
        return "review";
    }

    /** Puts the item back with a reason, keeping its place in the queue (FR-REV-04). */
    @PostMapping("/review/{id}/return")
    public String returnToQueue(@PathVariable Long id,
                                @RequestParam String reason,
                                RedirectAttributes redirect) {
        if (reason == null || reason.isBlank()) {
            redirect.addFlashAttribute("problem",
                    "Say why you are putting this item back, so the next reviewer does not "
                            + "repeat your work");
            return "redirect:/review/" + id;
        }
        reviews.returnToQueue(id, reason);
        redirect.addFlashAttribute("notice", "Returned to the queue with its priority unchanged");
        return "redirect:/worklist";
    }

    /** A fuzzy search with the reviewer's own parameters (FR-FUZ-01, FR-FUZ-02). */
    @PostMapping("/review/{id}/search/fuzzy")
    public String fuzzySearch(@PathVariable Long id,
                              @RequestParam(required = false) String family,
                              @RequestParam(required = false) String given,
                              @RequestParam(required = false) String gender,
                              @RequestParam(required = false) String birthdate,
                              @RequestParam(required = false) String postcode,
                              @RequestParam(defaultValue = "true") boolean history,
                              Model model) {
        ReviewItem item = reviews.claim(id).item();
        ReviewView view = reviews.open(item);

        PdsSearchResult result = searches.fuzzy(item, family, given, gender, birthdate,
                postcode, history);

        model.addAttribute("view", view);
        model.addAttribute("decisions", Decision.values());
        model.addAttribute("nonFuzzy", searches.nonFuzzy(item, view.derived()));
        model.addAttribute("fuzzy", result);
        model.addAttribute("fuzzyForm", new FuzzyForm(family, given, gender, birthdate,
                postcode, history));
        model.addAttribute("activeTab", "fuzzy");
        return "review";
    }

    /** Finds practices in ODS, then narrows PDS to one (FR-ODS-01 … FR-ODS-04). */
    @PostMapping("/review/{id}/practices")
    public String findPractices(@PathVariable Long id,
                                @RequestParam(required = false) String practiceName,
                                @RequestParam(required = false) String practicePostcode,
                                @RequestParam(required = false) String practiceTown,
                                @RequestParam(defaultValue = "false") boolean includeInactive,
                                @RequestParam(defaultValue = "false") boolean nearPatient,
                                Model model) {
        ReviewItem item = reviews.claim(id).item();
        ReviewView view = reviews.open(item);

        String postcodeFragment = practicePostcode;
        if (nearPatient) {
            // Only the outward code of the patient's postcode crosses to an unauthenticated
            // service, which is all ODS's leading-match semantics need (FR-ODS-11).
            PostcodeNormaliser.Result patientPostcode = view.derived().postcode();
            postcodeFragment = patientPostcode.usable() ? patientPostcode.outwardCode() : null;
        }

        OdsClient.PracticeResult practices = searches.findPractices(item,
                new OdsClient.PracticeQuery(practiceName, true, postcodeFragment,
                        practiceTown, includeInactive, 0));

        model.addAttribute("view", view);
        model.addAttribute("decisions", Decision.values());
        model.addAttribute("nonFuzzy", searches.nonFuzzy(item, view.derived()));
        model.addAttribute("practices", practices);
        model.addAttribute("activeTab", "practice");
        return "review";
    }

    /** The PDS search narrowed to the chosen practice. */
    @PostMapping("/review/{id}/search/practice")
    public String searchByPractice(@PathVariable Long id,
                                   @RequestParam String practiceOdsCode,
                                   @RequestParam(required = false) String practiceName,
                                   Model model) {
        ReviewItem item = reviews.claim(id).item();
        ReviewView view = reviews.open(item);

        PdsSearchResult result = searches.byGpPractice(item, view.derived(), practiceOdsCode);

        model.addAttribute("view", view);
        model.addAttribute("decisions", Decision.values());
        model.addAttribute("nonFuzzy", searches.nonFuzzy(item, view.derived()));
        model.addAttribute("byPractice", result);
        model.addAttribute("chosenPractice", practiceName == null ? practiceOdsCode : practiceName);
        model.addAttribute("activeTab", "practice");
        return "review";
    }

    /**
     * Discloses the triage pass, on request only. Not rendered with the page because it is bulk
     * patient data, and its disclosure is audited separately (FR-REV-06).
     */
    @GetMapping("/review/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        ReviewItem item = reviews.claim(id).item();
        if (item == null) {
            return ResponseEntity.notFound().build();
        }
        return reviews.disclosePdf(item)
                .map(base64 -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "inline; filename=\"triage-pass.pdf\"")
                        .body(Base64.getDecoder().decode(base64)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Records the terminal decision (FR-DEC-01 … FR-DEC-05). */
    @PostMapping("/review/{id}/decision")
    public String decide(@PathVariable Long id,
                         @RequestParam Decision decision,
                         @RequestParam(required = false) String note,
                         @RequestParam(required = false) String existingRecordId,
                         @RequestParam(required = false) String nhsNumber,
                         @RequestParam(defaultValue = "false") boolean confirmed,
                         Model model,
                         RedirectAttributes redirect) {

        DecisionRequest request = new DecisionRequest(decision, note, existingRecordId,
                nhsNumber, confirmed);
        DecisionService.Result result = decisions.record(id, request);

        if (result.recorded()) {
            redirect.addFlashAttribute("notice", decision.registers()
                    ? "Recorded. The patient will be registered."
                    : "Closed. No patient will be registered.");
            return "redirect:/worklist";
        }

        ReviewItem item = reviews.claim(id).item();
        ReviewView view = reviews.open(item);
        model.addAttribute("view", view);
        model.addAttribute("decisions", Decision.values());
        model.addAttribute("nonFuzzy", searches.nonFuzzy(item, view.derived()));
        model.addAttribute("decisionProblems", result.problems());
        model.addAttribute("attemptedDecision", decision);
        model.addAttribute("attemptedNote", note);
        model.addAttribute("attemptedExistingRecordId", existingRecordId);
        model.addAttribute("attemptedNhsNumber", nhsNumber);
        model.addAttribute("activeTab", "decision");
        return "review";
    }

    /** What the reviewer typed into the fuzzy form, so a refused search comes back filled in. */
    public record FuzzyForm(String family, String given, String gender, String birthdate,
                            String postcode, boolean history) {

        public static FuzzyForm empty() {
            return new FuzzyForm(null, null, null, null, null, true);
        }
    }

    /** Convenience for the template: the decisions that register, and those that close. */
    public static List<Decision> registering() {
        return List.of(Decision.MATCHED, Decision.ACCEPTED_NOT_ON_PDS,
                Decision.ACCEPTED_PATIENT_UNAVAILABLE);
    }

    public static List<Decision> closing() {
        return List.of(Decision.CLOSED_REGISTERED_MANUALLY, Decision.CLOSED_NO_LONGER_REQUIRED);
    }
}
