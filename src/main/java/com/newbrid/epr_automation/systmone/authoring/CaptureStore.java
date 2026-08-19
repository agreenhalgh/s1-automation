package com.newbrid.epr_automation.systmone.authoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The captures this tool is currently working on, held <b>in memory only</b>.
 *
 * <p>A screenshot of a live SystmOne session is patient data. Writing it to disk would create
 * an unmanaged copy of clinical information outside the retention and redaction rules the rest
 * of the system is held to (§5.4, NFR-SEC), so captures live here, are capped, are evicted
 * oldest-first, and can be discarded outright. Nothing here survives a restart, which for
 * authoring material is the correct trade.</p>
 */
@Component
@Profile("authoring")
public class CaptureStore {

    private static final Logger log = LoggerFactory.getLogger(CaptureStore.class);

    /**
     * One captured screen. Both the PNG bytes (to serve to the browser) and the decoded image
     * (to match against) are kept: decoding a full-screen PNG per match would dominate the
     * cost of locating a handful of templates.
     *
     * @param crops regions of this capture already saved as templates, newest last
     */
    public record Capture(String id, String workerId, byte[] png, BufferedImage image,
                          int width, int height, double uiScale, Instant capturedAt,
                          List<Crop> crops) {

        public Capture {
            crops = crops == null ? new ArrayList<>() : crops;
        }
    }

    /** A template definition, in the pixel space of the capture it was cropped from. */
    public record Crop(String templateName, Rectangle rect) {}

    private final int maxCaptures;
    private final Map<String, Capture> captures = new LinkedHashMap<>();

    public CaptureStore(AuthoringProperties props) {
        this.maxCaptures = Math.max(1, props.getMaxCaptures());
    }

    public synchronized void put(Capture capture) {
        captures.put(capture.id(), capture);
        while (captures.size() > maxCaptures) {
            String oldest = captures.keySet().iterator().next();
            captures.remove(oldest);
            log.debug("Evicted capture {} — holding at most {}", oldest, maxCaptures);
        }
    }

    public synchronized Optional<Capture> find(String id) {
        return Optional.ofNullable(captures.get(id));
    }

    /** @throws IllegalArgumentException if the capture has been discarded or evicted */
    public synchronized Capture require(String id) {
        Capture c = captures.get(id);
        if (c == null) {
            throw new IllegalArgumentException("Capture '" + id + "' is no longer held — it was "
                    + "discarded, or evicted after " + maxCaptures + " newer captures. Take another.");
        }
        return c;
    }

    /** Newest first, which is the order an author wants to see them in. */
    public synchronized List<Capture> list() {
        List<Capture> all = new ArrayList<>(captures.values());
        return all.reversed();
    }

    public synchronized boolean discard(String id) {
        return captures.remove(id) != null;
    }

    public synchronized int discardAll() {
        int held = captures.size();
        captures.clear();
        log.info("Discarded {} capture(s) at the author's request", held);
        return held;
    }

    /** Record that a region of this capture is now a registered template. */
    public synchronized void addCrop(String captureId, String templateName, Rectangle rect) {
        Capture capture = captures.get(captureId);
        if (capture == null) return;
        capture.crops().removeIf(c -> c.templateName().equals(templateName));
        capture.crops().add(new Crop(templateName, rect));
    }
}
