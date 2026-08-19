package com.newbrid.epr_automation.systmone.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Worker configuration (bound from {@code systmone.worker.*}). */
@ConfigurationProperties(prefix = "systmone.worker")
public class WorkerProperties {

    /** Base URL of the control plane, e.g. {@code https://control.internal:8443}. */
    private String controlPlaneUrl = "http://localhost:8080";

    /** Shared token presented to the control plane (FR-WRK-07). */
    private String token = "changeit";

    /** Stable worker id; defaults to the host name if left blank. */
    private String workerId = "";

    /** Optional capability/pool routing tag (FR-WRK-10). */
    private String capabilityTag = "";

    /** SystmOne window title substring used to find the top-level accessible window. */
    private String windowTitle = "SystmOne";

    /** Reported SystmOne version. */
    private String s1Version = "unknown";

    /** Directory for failure screenshots (NFR-OBS-02); subject to §5.4 retention/redaction. */
    private String screenshotDir = "./worker-screenshots";

    /** Max attempts to deliver the terminal outcome before dead-lettering the job (FR-WRK-05). */
    private int resultPostMaxAttempts = 5;

    public String getControlPlaneUrl() { return controlPlaneUrl; }
    public void setControlPlaneUrl(String v) { this.controlPlaneUrl = v; }
    public String getToken() { return token; }
    public void setToken(String v) { this.token = v; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String v) { this.workerId = v; }
    public String getCapabilityTag() { return capabilityTag; }
    public void setCapabilityTag(String v) { this.capabilityTag = v; }
    public String getWindowTitle() { return windowTitle; }
    public void setWindowTitle(String v) { this.windowTitle = v; }
    public String getS1Version() { return s1Version; }
    public void setS1Version(String v) { this.s1Version = v; }
    public String getScreenshotDir() { return screenshotDir; }
    public void setScreenshotDir(String v) { this.screenshotDir = v; }
    public int getResultPostMaxAttempts() { return resultPostMaxAttempts; }
    public void setResultPostMaxAttempts(int v) { this.resultPostMaxAttempts = v; }

    /** Effective worker id, falling back to the host name. */
    public String resolveWorkerId() {
        if (workerId != null && !workerId.isBlank()) return workerId;
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "worker-" + Long.toHexString(System.nanoTime());
        }
    }
}
