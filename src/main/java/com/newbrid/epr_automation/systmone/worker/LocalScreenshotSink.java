package com.newbrid.epr_automation.systmone.worker;

import com.newbrid.epr_automation.systmone.sdk.engine.ScreenshotSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists failure screenshots to a local directory and returns a file reference
 * (NFR-OBS-02). NOTE (§5.4): screenshots may contain patient data — a production sink
 * must encrypt at rest, apply redaction and enforce retention; this local sink is for
 * non-production/synthetic-data use.
 */
public class LocalScreenshotSink implements ScreenshotSink {

    private static final Logger log = LoggerFactory.getLogger(LocalScreenshotSink.class);

    private final Path dir;

    public LocalScreenshotSink(String dir) {
        this.dir = Path.of(dir);
    }

    @Override
    public String store(String runId, int stepIndex, BufferedImage image) {
        if (image == null) return null;
        try {
            Files.createDirectories(dir);
            String name = runId + "-step" + stepIndex + ".png";
            File out = dir.resolve(name).toFile();
            ImageIO.write(image, "png", out);
            return out.getAbsolutePath();
        } catch (Exception e) {
            log.warn("Failed to store failure screenshot: {}", e.toString());
            return null;
        }
    }
}
