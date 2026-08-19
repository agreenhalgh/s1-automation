package com.newbrid.epr_automation.systmone.automation.screen;

import java.awt.image.BufferedImage;

/**
 * Supplies template images by reference (id/name). The worker's implementation
 * fetches from the control-plane registry or a local cache directory (FR-REG-04).
 */
public interface TemplateStore {

    /** @return the template image, or {@code null} if the ref is unknown. */
    BufferedImage load(String ref);
}
