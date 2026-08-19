package com.newbrid.epr_automation.systmone.authoring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Capture-tool configuration (bound from {@code systmone.authoring.*}). */
@ConfigurationProperties(prefix = "systmone.authoring")
public class AuthoringProperties {

    /** Base URL of the control plane whose registry this tool reads and writes. */
    private String controlPlaneUrl = "http://localhost:8080";

    /** Shared token presented to the control-plane API (FR-WRK-07). */
    private String token = "changeit";

    /**
     * How long to wait for a worker to answer a capture request. Generous enough for window
     * activation plus a full-screen PNG encode, short enough that an offline worker is
     * reported rather than left spinning.
     */
    private int captureTimeoutMs = 15_000;

    /**
     * Captures held in memory at once, oldest evicted. Screenshots of a live clinical system
     * are patient data, so this tool never writes them to disk and keeps only a small
     * working set (§5.4).
     */
    private int maxCaptures = 6;

    /** Default match threshold offered when saving a new template (FR-SR-08). */
    private double defaultThreshold = 0.90;

    /** SystmOne version stamped on templates and locators authored here (FR-REG-02). */
    private String s1Version = "unknown";

    public String getControlPlaneUrl() { return controlPlaneUrl; }
    public void setControlPlaneUrl(String v) { this.controlPlaneUrl = v; }
    public String getToken() { return token; }
    public void setToken(String v) { this.token = v; }
    public int getCaptureTimeoutMs() { return captureTimeoutMs; }
    public void setCaptureTimeoutMs(int v) { this.captureTimeoutMs = v; }
    public int getMaxCaptures() { return maxCaptures; }
    public void setMaxCaptures(int v) { this.maxCaptures = v; }
    public double getDefaultThreshold() { return defaultThreshold; }
    public void setDefaultThreshold(double v) { this.defaultThreshold = v; }
    public String getS1Version() { return s1Version; }
    public void setS1Version(String v) { this.s1Version = v; }
}
