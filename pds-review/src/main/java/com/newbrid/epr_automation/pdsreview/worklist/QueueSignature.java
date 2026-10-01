package com.newbrid.epr_automation.pdsreview.worklist;

import com.newbrid.epr_automation.pdsreview.domain.ReviewItem;

import java.util.List;

/**
 * A short, stable fingerprint of what the worklist is currently showing.
 *
 * <p>This is what lets a background refresh be cheap and quiet (FR-WL-11a). The client sends the
 * signature of the list on screen; if it still matches, the server answers {@code 204} and
 * nothing happens — no markup rendered, no DOM replaced, and above all no audit row. A reviewer
 * with the worklist open for an afternoon would otherwise leave several hundred entries saying
 * nothing changed, and an audit trail that cannot be read is not one (FR-AUD-06).
 *
 * <p>It covers the item <em>and its state</em>, because the visible list changes in three ways
 * that all matter: an item is admitted, an item is decided and leaves, or another reviewer takes
 * one and it becomes "being reviewed" (FR-WL-06). Waiting times are deliberately excluded — they
 * change every second, and refreshing the page every second to advance a clock would be the
 * opposite of the point.
 */
public final class QueueSignature {

    private QueueSignature() {}

    /**
     * The fingerprint of one page of the worklist.
     *
     * <p>{@code String.hashCode} rather than a cryptographic digest: this is a
     * change-detection token, not a security boundary — a client that forges one only makes its
     * own list refresh, which it can do anyway — and the algorithm is specified by the JDK, so
     * two nodes behind a load balancer agree on it.
     */
    public static String of(List<ReviewItem> items, long totalElements) {
        StringBuilder shape = new StringBuilder().append(totalElements).append(':');
        for (ReviewItem item : items) {
            shape.append(item.getId()).append(item.getState().name().charAt(0)).append(',');
        }
        return Integer.toHexString(shape.toString().hashCode());
    }

    /** Whether the client is already showing this. Absent or blank means "show me anyway". */
    public static boolean unchanged(String seen, String current) {
        return seen != null && !seen.isBlank() && seen.equals(current);
    }
}
