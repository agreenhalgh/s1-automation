package com.newbrid.epr_automation.systmone.worker;

import tools.jackson.databind.ObjectMapper;
import com.newbrid.epr_automation.systmone.common.dto.ExceptionInfo;
import com.newbrid.epr_automation.systmone.common.dto.RunJobMessage;
import com.newbrid.epr_automation.systmone.common.dto.StatusReport;
import com.newbrid.epr_automation.systmone.common.model.RunStatus;
import com.newbrid.epr_automation.systmone.sdk.engine.BotEngine;
import com.newbrid.epr_automation.systmone.sdk.engine.RunResult;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Consumes jobs from the work queue and executes them (FR-WRK-01). Enforces the delivery
 * contract:
 * <ul>
 *   <li>Runs one job at a time (prefetch 1, FR-WRK-02).</li>
 *   <li>POSTs the terminal outcome with retry-backoff and <b>acks only after it is
 *       accepted</b> (FR-WRK-04); if it can never be delivered, the message is rejected to
 *       the dead-letter queue (FR-WRK-05).</li>
 * </ul>
 * A crash before ack causes redelivery to another worker; combined with the idempotent
 * results endpoint this yields at-least-once delivery with exactly-once effect.
 */
@Component
@Profile("worker")
public class JobListener {

    private static final Logger log = LoggerFactory.getLogger(JobListener.class);

    private final BotEngine engine;
    private final ControlPlaneClient client;
    private final ObjectMapper mapper;
    private final WorkerProperties props;
    private final WorkerMetrics metrics;

    public JobListener(BotEngine engine, ControlPlaneClient client, ObjectMapper mapper,
                       WorkerProperties props, WorkerMetrics metrics) {
        this.engine = engine;
        this.client = client;
        this.mapper = mapper;
        this.props = props;
        this.metrics = metrics;
    }

    @RabbitListener(queues = "#{@jobsQueue.name}", containerFactory = "workerListenerFactory")
    public void onJob(Message message, Channel channel) {
        long tag = message.getMessageProperties().getDeliveryTag();
        String runId = null;
        StatusReport terminal;
        // Started before the body is even parsed: an unparseable job still occupied this
        // worker, and a job counted only on the happy path measures the wrong thing.
        long startedAt = System.nanoTime();
        metrics.jobStarted();
        try {
            RunJobMessage job = mapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8),
                    RunJobMessage.class);
            runId = job.runId();
            log.info("Worker {} picked up run {} (bot {})", client.workerId(), runId, job.bot().botId());

            safePostRunning(runId);
            PostingRunListener listener = new PostingRunListener(client, runId, client.workerId(), metrics);
            RunResult result = engine.run(job.bot(), job.input(), runId, listener, () -> false);
            terminal = new StatusReport(result.status(), result.error(), result.outputs());
        } catch (Exception e) {
            log.error("Run {} failed in the worker: {}", runId, e.toString(), e);
            terminal = new StatusReport(RunStatus.FAILED, ExceptionInfo.from(e, "worker execution"), Map.of());
        } finally {
            // In a finally so that an Error escaping the run — which the catch above
            // deliberately does not swallow — cannot leave this workstation reading as
            // permanently busy for as long as the process lives.
            metrics.jobEnded();
        }
        // Outside it, because naming the outcome needs an outcome, and both branches above
        // set one.
        metrics.jobOutcome(terminal.status(), Duration.ofNanos(System.nanoTime() - startedAt));

        // Ack only once the terminal outcome is durably recorded by the control plane.
        boolean delivered = runId != null && postTerminalWithRetry(runId, terminal);
        try {
            if (delivered) {
                channel.basicAck(tag, false);
            } else {
                log.error("Could not deliver terminal outcome for run {} after {} attempts — dead-lettering",
                        runId, props.getResultPostMaxAttempts());
                channel.basicNack(tag, false, false);
            }
        } catch (Exception ackError) {
            log.error("Failed to ack/nack run {}: {}", runId, ackError.toString());
        }
    }

    private boolean postTerminalWithRetry(String runId, StatusReport terminal) {
        int max = Math.max(1, props.getResultPostMaxAttempts());
        long backoff = 500;
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                client.postStatus(runId, terminal);
                return true;
            } catch (Exception e) {
                log.warn("Terminal POST for run {} attempt {}/{} failed: {}", runId, attempt, max, e.toString());
                sleep(backoff);
                backoff = Math.min(backoff * 2, 10_000);
            }
        }
        return false;
    }

    private void safePostRunning(String runId) {
        try {
            client.postStatus(runId, new StatusReport(RunStatus.RUNNING, null, Map.of()));
        } catch (Exception e) {
            log.debug("RUNNING status POST failed (non-fatal): {}", e.toString());
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
