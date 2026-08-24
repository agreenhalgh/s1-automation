package com.newbrid.epr_automation.systmone.worker;

import com.newbrid.epr_automation.systmone.common.dto.ExceptionInfo;
import com.newbrid.epr_automation.systmone.common.dto.Heartbeat;
import com.newbrid.epr_automation.systmone.common.dto.StepReport;
import com.newbrid.epr_automation.systmone.common.model.RunStatus;
import com.newbrid.epr_automation.systmone.sdk.engine.RunListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Bridges engine callbacks to control-plane POSTs (FR-WRK-03): each completed step and
 * each progress ping is reported as it happens. Step/heartbeat posts are best-effort —
 * the authoritative, ack-gating terminal POST is handled by {@link JobListener}
 * (FR-WRK-04).
 *
 * <p>Each step is also counted locally ({@link WorkerMetrics}) before it is posted, so the
 * numbers survive a control plane this worker cannot reach.
 */
public class PostingRunListener implements RunListener {

    private static final Logger log = LoggerFactory.getLogger(PostingRunListener.class);

    private final ControlPlaneClient client;
    private final String runId;
    private final String workerId;
    private final WorkerMetrics metrics;

    public PostingRunListener(ControlPlaneClient client, String runId, String workerId,
                              WorkerMetrics metrics) {
        this.client = client;
        this.runId = runId;
        this.workerId = workerId;
        this.metrics = metrics;
    }

    @Override
    public void onProgress(int stepIndex) {
        try {
            client.heartbeat(runId, new Heartbeat(workerId, stepIndex));
        } catch (Exception e) {
            log.debug("Heartbeat POST failed (non-fatal): {}", e.toString());
        }
    }

    @Override
    public void onStepComplete(StepReport report) {
        metrics.stepCompleted(report);
        try {
            client.postStep(runId, report);
        } catch (Exception e) {
            log.warn("Step {} POST failed (non-fatal, terminal report is authoritative): {}",
                    report.index(), e.toString());
        }
    }

    @Override
    public void onRunComplete(RunStatus status, ExceptionInfo error, Map<String, String> outputs) {
        // Terminal reporting is done by JobListener so it can gate the AMQP ack (FR-WRK-04).
    }
}
