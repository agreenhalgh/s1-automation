package com.newbrid.epr_automation.pdsreview.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Puts back the items nobody came back to (FR-REV-02).
 *
 * <p>Without this, a reviewer who closes their laptop mid-review strands a patient behind a lock
 * that never expires — and the more urgent the patient, the worse that is, because the queue is
 * ordered so they were picked up first.
 */
@Component
public class LockSweeper {

    private static final Logger log = LoggerFactory.getLogger(LockSweeper.class);

    private final ReviewService reviews;

    public LockSweeper(ReviewService reviews) {
        this.reviews = reviews;
    }

    @Scheduled(fixedDelayString = "${systmone.pds-review.review.lock-sweep-interval:1m}")
    public void reclaim() {
        int reclaimed = reviews.reclaimExpiredLocks();
        if (reclaimed > 0) {
            log.info("Returned {} item(s) to the queue after their locks expired", reclaimed);
        }
    }
}
