package com.newbrid.epr_automation.pdsreview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PDS match review: the worklist for triage presentations whose patient the calling application
 * could not match to the NHS Personal Demographics Service.
 *
 * <p>A presentation that arrives without PDS demographics describes a patient nobody has yet
 * identified, and no patient can be registered on the EPR until somebody does. This application
 * turns that gap into a queue, puts a CIS2-authenticated reviewer in front of it with the search
 * tools to close it, and records what they decided and why.
 *
 * <p>Its requirements are specified in {@code pds-review/docs/requirements.md}. Two things in
 * there shape everything else and are worth knowing before reading any code here:
 *
 * <ul>
 *   <li><b>No match is ever accepted automatically</b> — at any score, including 1.0. A PDS
 *       score is evidence for a human decision, not the decision (NFR-SAFE-01). Registering a
 *       presentation against the wrong person's record is the critical risk this application
 *       exists in the shadow of.</li>
 *   <li><b>This service cannot pseudonymise.</b> The gateway hashes the NHS number out of its
 *       audit trail on purpose; that option is not open here, because the whole function is to
 *       show a clinician a patient's details beside candidate matches and record which one they
 *       chose. The controls that follow are different in kind from the gateway's, not in
 *       degree.</li>
 * </ul>
 *
 * <p>Like {@code gateway/}, this is a standalone sub-project sharing no code with the control
 * plane — decisions reach it over the same audited REST front door any other integration uses.
 */
@SpringBootApplication
public class PdsReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(PdsReviewApplication.class, args);
    }
}
