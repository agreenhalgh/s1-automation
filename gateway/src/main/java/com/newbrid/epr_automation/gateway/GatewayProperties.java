package com.newbrid.epr_automation.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Gateway configuration, bound from {@code systmone.gateway.*}. */
@ConfigurationProperties(prefix = "systmone.gateway")
public class GatewayProperties {

    private final Inbound inbound = new Inbound();
    private final ControlPlane controlPlane = new ControlPlane();
    private final Run run = new Run();
    private final Audit audit = new Audit();
    private final Document document = new Document();
    private final DocumentViewer documentViewer = new DocumentViewer();

    public Inbound getInbound() { return inbound; }
    public ControlPlane getControlPlane() { return controlPlane; }
    public Run getRun() { return run; }
    public Audit getAudit() { return audit; }
    public Document getDocument() { return document; }
    public DocumentViewer getDocumentViewer() { return documentViewer; }

    /** The gateway's own front door. */
    public static class Inbound {

        /**
         * Shared token a caller presents in {@code X-Gateway-Token}. The same seam as the control
         * plane's worker token and with the same intended replacement — OAuth2 client credentials
         * or mTLS for anything real. What it must not be is absent: this endpoint launches
         * automation against live patient records.
         */
        private String token = "changeit";

        public String getToken() { return token; }
        public void setToken(String v) { this.token = v; }
    }

    /** Where to send the transformed request. */
    public static class ControlPlane {

        private String url = "http://localhost:8080";

        /** Presented as {@code X-Worker-Token} — the control plane machine surface (FR-WRK-07). */
        private String token = "changeit";

        /** Timeout for a single call to the control plane, not for the whole wait. */
        private int requestTimeoutMs = 10_000;

        public String getUrl() { return url; }
        public void setUrl(String v) { this.url = v; }
        public String getToken() { return token; }
        public void setToken(String v) { this.token = v; }
        public int getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(int v) { this.requestTimeoutMs = v; }
    }

    public static class DocumentViewer {
        private String url = "http://localhost:8084";
        private String organisationId = "ORG_ID";
        private int requestTimeoutMs = 10_000;
        public String getUrl() { return url; }
        public void setUrl(String v) { this.url = v; }
        public int getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(int v) { this.requestTimeoutMs = v; }
    }

    /** How long, and how often, to wait for a definitive outcome. */
    public static class Run {

        /**
         * Default wait when a submission does not ask for one. Deliberately shorter than a bot
         * global timeout would typically be: a caller who wants to wait longer must say so,
         * because the cost of waiting is a held connection and a held request thread.
         */
        private int defaultWaitSeconds = 120;

        /**
         * Ceiling on what a submission may ask for, whatever it asks for. Without it a single
         * caller could hold a request thread for as long as they liked.
         */
        private int maxWaitSeconds = 1800;

        /**
         * Gap between polls of the run. Each poll is a database read on the control plane, so
         * this trades answer latency against load: at 2s a 30s run is reported within 2s of
         * finishing, at a cost of 15 queries.
         */
        private long pollIntervalMs = 2_000;

        public int getDefaultWaitSeconds() { return defaultWaitSeconds; }
        public void setDefaultWaitSeconds(int v) { this.defaultWaitSeconds = v; }
        public int getMaxWaitSeconds() { return maxWaitSeconds; }
        public void setMaxWaitSeconds(int v) { this.maxWaitSeconds = v; }
        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long v) { this.pollIntervalMs = v; }
    }

    /** How much of a request is safe to keep. */
    public static class Audit {

        /**
         * Key for the HMAC that turns a patient identifier into an audit reference. A plain
         * digest would not do: there are only ten billion possible NHS numbers, so an unkeyed
         * SHA-256 of one is reversible by exhaustive search in minutes, which would make this
         * table a store of patient identifiers in all but name (NFR-SEC-03, NFR-SEC-05).
         *
         * <p>Rotating this key breaks the link between references written before and after, which
         * is the point of it being configurable — and the reason it must be set once and kept,
         * rather than regenerated per deployment.
         */
        private String pseudonymKey = "changeit";

        public String getPseudonymKey() { return pseudonymKey; }
        public void setPseudonymKey(String v) { this.pseudonymKey = v; }
    }

    /** Where submitted PDFs are stored. */
    public static class Document {

        /**
         * Root of the document store. Everything written lands under a dated folder inside it,
         * and a resolved path that escapes it is a rejected request rather than a sanitised one.
         *
         * <p>A local directory by default so a developer stack needs nothing mounted. In a real
         * deployment this is a share the file is safe to sit on: the documents are clinical
         * correspondence, so it wants the same encryption-at-rest and retention treatment as the
         * failure screenshots (NFR-SEC-03).
         */
        private String root = "./gateway-documents";

        /**
         * Largest PDF accepted, after decoding. Checked on the decoded bytes rather than on the
         * base64, because the base64 length only bounds it to within a third and the number that
         * matters is what goes to disk.
         */
        private long maxBytes = 15L * 1024 * 1024;

        public String getRoot() { return root; }
        public void setRoot(String v) { this.root = v; }
        public long getMaxBytes() { return maxBytes; }
        public void setMaxBytes(long v) { this.maxBytes = v; }
    }
}
