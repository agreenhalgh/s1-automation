package com.newbrid.epr_automation.systmone.worker;

import com.newbrid.epr_automation.systmone.automation.screen.TemplateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.concurrent.ConcurrentHashMap;

/** {@link TemplateStore} that fetches template images from the control-plane registry and caches them. */
public class HttpTemplateStore implements TemplateStore {

    private static final Logger log = LoggerFactory.getLogger(HttpTemplateStore.class);

    private final ControlPlaneClient client;
    private final ConcurrentHashMap<String, BufferedImage> cache = new ConcurrentHashMap<>();

    public HttpTemplateStore(ControlPlaneClient client) {
        this.client = client;
    }

    @Override
    public BufferedImage load(String ref) {
        return cache.computeIfAbsent(ref, this::fetch);
    }

    private BufferedImage fetch(String ref) {
        byte[] bytes = client.fetchTemplate(ref);
        if (bytes == null || bytes.length == 0) return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            log.warn("Template '{}' is not a readable image: {}", ref, e.toString());
            return null;
        }
    }
}
