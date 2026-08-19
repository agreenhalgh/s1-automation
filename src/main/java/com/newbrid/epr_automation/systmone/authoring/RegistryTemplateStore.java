package com.newbrid.epr_automation.systmone.authoring;

import com.newbrid.epr_automation.systmone.automation.screen.TemplateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Template images for matching, fetched from the registry over HTTP — the authoring
 * counterpart of the worker's store, so both resolve a {@code templateRef} to the same bytes.
 *
 * <p>Cached, but invalidated the moment this tool re-uploads a template: authoring is a loop of
 * crop, test, re-crop, and a stale cache would show the author a result for the previous
 * version of the template they just replaced.</p>
 */
@Component
@Profile("authoring")
public class RegistryTemplateStore implements TemplateStore {

    private static final Logger log = LoggerFactory.getLogger(RegistryTemplateStore.class);

    private final RegistryClient registry;
    private final ConcurrentHashMap<String, BufferedImage> cache = new ConcurrentHashMap<>();

    public RegistryTemplateStore(RegistryClient registry) {
        this.registry = registry;
    }

    @Override
    public BufferedImage load(String ref) {
        return cache.computeIfAbsent(ref, this::fetch);
    }

    public void invalidate(String ref) {
        cache.remove(ref);
    }

    public void invalidateAll() {
        cache.clear();
    }

    private BufferedImage fetch(String ref) {
        try {
            byte[] bytes = registry.templateImage(ref);
            if (bytes == null || bytes.length == 0) return null;
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            log.warn("Could not load template '{}' from the registry: {}", ref, e.toString());
            return null;
        }
    }
}
