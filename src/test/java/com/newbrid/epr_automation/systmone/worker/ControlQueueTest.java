package com.newbrid.epr_automation.systmone.worker;

import com.newbrid.epr_automation.systmone.config.RabbitTopologyConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The authoring capture channel's queue properties, which are load-bearing in two directions and
 * easy to "tidy" into something that does not work.
 *
 * <p>Auto-delete is behavioural: it is what makes a capture request to a stopped worker fail fast
 * instead of being delivered stale on restart. Durable is a broker constraint: RabbitMQ 4 removed
 * transient non-exclusive queues, and declaring one kills the whole connection with
 * {@code INTERNAL_ERROR - Feature 'transient_nonexcl_queues' is deprecated} — which manifests as a
 * worker that connects, retries and never consumes.</p>
 */
class ControlQueueTest {

    private static Queue controlQueue(String workerId) {
        WorkerProperties props = new WorkerProperties();
        props.setWorkerId(workerId);
        return new WorkerConfig().controlQueue(props);
    }

    @Test
    void isNamedAfterTheWorkerSoCapturesReachOneMachine() {
        assertEquals("systmone.control.ward-pc-3", controlQueue("ward-pc-3").getName());
        assertEquals(RabbitTopologyConfig.controlQueueName("ward-pc-3"), controlQueue("ward-pc-3").getName());
    }

    @Test
    void isAutoDeleteSoRequestsToAStoppedWorkerAreNotQueuedForLater() {
        assertTrue(controlQueue("w1").isAutoDelete());
    }

    @Test
    void isDurableBecauseRabbitMq4RejectsTransientNonExclusiveQueues() {
        assertTrue(controlQueue("w1").isDurable(),
                "a non-durable, non-exclusive queue fails declaration on RabbitMQ 4");
    }

    @Test
    void isNotExclusiveSoTheListenerCanRedeclareItOnAnyChannel() {
        assertFalse(controlQueue("w1").isExclusive());
    }
}
