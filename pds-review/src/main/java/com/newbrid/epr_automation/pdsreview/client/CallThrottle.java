package com.newbrid.epr_automation.pdsreview.client;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/**
 * A token bucket in front of a national API.
 *
 * <p>This exists for somebody else's benefit. PDS rate limits are per-organisation, not per
 * application, so a review queue working through a backlog can exhaust the trust's quota and stop
 * other clinical systems reaching PDS at all (NFR-PERF-04). Throttling here is what stops this
 * application's busy afternoon becoming somebody else's outage.
 *
 * <p>It blocks rather than rejects. A reviewer waiting an extra 200ms for a search is a
 * non-event; a search refused because the application was busy would push them towards deciding
 * without one.
 */
public final class CallThrottle {

    private final long intervalNanos;
    private final long burstNanos;

    /** Monotonic: the earliest time the next permit is available. */
    private long nextPermitNanos;

    /**
     * @param permitsPerSecond how many calls per second. Zero or less disables throttling, which
     *                        is only appropriate against a sandbox
     */
    public CallThrottle(int permitsPerSecond) {
        this.intervalNanos = permitsPerSecond <= 0 ? 0L : Duration.ofSeconds(1).toNanos() / permitsPerSecond;
        // One second of credit, so an idle application does not make a reviewer wait for the
        // first search of the morning.
        this.burstNanos = Duration.ofSeconds(1).toNanos();
        this.nextPermitNanos = System.nanoTime();
    }

    /** Waits, if it has to, until this call may go out. */
    public void acquire() {
        if (intervalNanos == 0L) {
            return;
        }
        long waitNanos;
        synchronized (this) {
            long now = System.nanoTime();
            if (nextPermitNanos < now - burstNanos) {
                nextPermitNanos = now - burstNanos;
            }
            waitNanos = Math.max(0L, nextPermitNanos - now);
            nextPermitNanos += intervalNanos;
        }
        if (waitNanos > 0L) {
            LockSupport.parkNanos(waitNanos);
        }
    }
}
