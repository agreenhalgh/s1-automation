package com.newbrid.epr_automation.systmone.authoring;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store is the tool's only copy of a clinical screenshot, so its bounds and its discard
 * behaviour are the controls that keep that copy small and short-lived (§5.4).
 */
class CaptureStoreTest {

    private static CaptureStore.Capture capture(String id) {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        return new CaptureStore.Capture(id, "worker-1", new byte[]{1, 2, 3}, image, 8, 8, 1.0,
                Instant.now(), null);
    }

    private static CaptureStore storeHolding(int max) {
        AuthoringProperties props = new AuthoringProperties();
        props.setMaxCaptures(max);
        return new CaptureStore(props);
    }

    @Test
    void holdsAtMostTheConfiguredNumberOfCapturesAndEvictsTheOldest() {
        CaptureStore store = storeHolding(2);

        store.put(capture("a"));
        store.put(capture("b"));
        store.put(capture("c"));

        assertTrue(store.find("a").isEmpty(), "the oldest capture is evicted, not kept");
        assertTrue(store.find("b").isPresent());
        assertTrue(store.find("c").isPresent());
    }

    @Test
    void listsNewestFirst() {
        CaptureStore store = storeHolding(5);
        store.put(capture("a"));
        store.put(capture("b"));

        List<String> ids = store.list().stream().map(CaptureStore.Capture::id).toList();

        assertEquals(List.of("b", "a"), ids);
    }

    @Test
    void discardRemovesOneAndDiscardAllReportsWhatItCleared() {
        CaptureStore store = storeHolding(5);
        store.put(capture("a"));
        store.put(capture("b"));

        assertTrue(store.discard("a"));
        assertFalse(store.discard("a"), "discarding twice is not a second removal");
        assertEquals(1, store.discardAll());
        assertTrue(store.list().isEmpty());
    }

    @Test
    void requiringAnEvictedCaptureExplainsWhatHappened() {
        CaptureStore store = storeHolding(1);
        store.put(capture("a"));
        store.put(capture("b"));

        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> store.require("a"));

        assertTrue(ex.getMessage().contains("discarded"), ex.getMessage());
    }

    @Test
    void recordingACropTwiceForTheSameTemplateReplacesIt() {
        CaptureStore store = storeHolding(2);
        store.put(capture("a"));

        store.addCrop("a", "button", new Rectangle(1, 2, 10, 10));
        store.addCrop("a", "button", new Rectangle(5, 6, 20, 20));

        List<CaptureStore.Crop> crops = store.require("a").crops();
        assertEquals(1, crops.size(), "re-cropping a template revises it rather than duplicating");
        assertEquals(5, crops.getFirst().rect().x);
    }
}
