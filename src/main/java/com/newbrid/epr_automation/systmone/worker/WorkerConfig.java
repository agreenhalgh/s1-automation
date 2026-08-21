package com.newbrid.epr_automation.systmone.worker;

import com.newbrid.epr_automation.systmone.automation.jab.AccessBridge;
import com.newbrid.epr_automation.systmone.automation.jab.JabBackend;
import com.newbrid.epr_automation.systmone.automation.jab.JabInspector;
import com.newbrid.epr_automation.systmone.automation.jab.JnaAccessBridge;
import com.newbrid.epr_automation.systmone.automation.screen.RobotInput;
import com.newbrid.epr_automation.systmone.automation.screen.ScreenBackend;
import com.newbrid.epr_automation.systmone.automation.screen.TemplateStore;
import com.newbrid.epr_automation.systmone.automation.window.NoopWindowControl;
import com.newbrid.epr_automation.systmone.automation.window.Win32WindowControl;
import com.newbrid.epr_automation.systmone.config.RabbitTopologyConfig;
import com.newbrid.epr_automation.systmone.sdk.engine.WindowControl;
import com.newbrid.epr_automation.systmone.sdk.engine.BotEngine;
import com.newbrid.epr_automation.systmone.sdk.engine.KeyboardControl;
import com.newbrid.epr_automation.systmone.sdk.engine.ScreenshotSink;
import com.newbrid.epr_automation.systmone.sdk.locator.BackendRegistry;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Assembles a worker: the two automation backends behind the engine, the control-plane
 * client, and a RabbitMQ listener container configured for the delivery semantics the
 * requirements demand — manual ack, prefetch 1, reject-without-requeue → DLQ
 * (FR-WRK-02/04/05).
 */
@Configuration
@Profile("worker")
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerConfig {

    @Bean
    public ControlPlaneClient controlPlaneClient(WorkerProperties props) {
        return new ControlPlaneClient(props.getControlPlaneUrl(), props.getToken(), props.resolveWorkerId());
    }

    @Bean
    public RobotInput robotInput() {
        return new RobotInput();
    }

    @Bean
    public AccessBridge accessBridge() {
        return new JnaAccessBridge();
    }

    /**
     * Foreground control for ACTIVATE steps. Windows only; elsewhere a no-op that fails
     * an ACTIVATE step explicitly rather than letting the run type into another window.
     */
    @Bean
    public WindowControl windowControl(WorkerProperties props) {
        if (com.sun.jna.Platform.isWindows()) {
            return new Win32WindowControl(props.getWindowTitle());
        }
        return new NoopWindowControl("window activation needs Windows; this host is "
                + System.getProperty("os.name"));
    }

    /**
     * Reads the accessibility tree for the authoring tool. Shares the window-title filter with
     * {@link JabBackend} so the tool inspects the same windows a run resolves locators in.
     */
    @Bean
    public JabInspector jabInspector(AccessBridge bridge, WorkerProperties props) {
        return new JabInspector(bridge, props.getWindowTitle());
    }

    @Bean
    public TemplateStore templateStore(ControlPlaneClient client) {
        return new HttpTemplateStore(client);
    }

    @Bean
    public ScreenshotSink screenshotSink(WorkerProperties props) {
        return new LocalScreenshotSink(props.getScreenshotDir());
    }

    @Bean
    public BackendRegistry backendRegistry(AccessBridge bridge, RobotInput robot,
                                           TemplateStore templateStore, WorkerProperties props) {
        return new BackendRegistry()
                .register(new JabBackend(bridge, robot, props.getWindowTitle()))
                .register(new ScreenBackend(templateStore, robot));
    }

    /**
     * @param keyboard the same {@link RobotInput} the backends drive — it is the engine's
     *                 {@link KeyboardControl} too, which is what lets a KEYS step carry no locator
     */
    @Bean
    public BotEngine botEngine(BackendRegistry registry, ScreenshotSink sink, WindowControl windows,
                               RobotInput keyboard) {
        return new BotEngine(registry, sink, windows, keyboard);
    }

    /**
     * This worker's own command queue, addressed by worker id — how the authoring tool asks
     * <em>this</em> workstation for a screenshot (FR-TOOL-02).
     *
     * <p><b>Auto-delete</b> is the point: the queue exists only while this worker is consuming,
     * so a capture request aimed at a stopped worker is unroutable and fails fast at the caller's
     * reply timeout. Queuing it instead would deliver a burst of stale captures on restart, of a
     * screen the author stopped looking at long ago.</p>
     *
     * <p><b>Durable</b> not by choice but by necessity: RabbitMQ 4 removed transient
     * non-exclusive queues ({@code transient_nonexcl_queues}), and declaring one now fails the
     * whole connection with {@code INTERNAL_ERROR}. Durability only decides whether the queue
     * survives a broker restart, which for an auto-delete queue with no live consumer is moot.</p>
     */
    @Bean
    public Queue controlQueue(WorkerProperties props) {
        return QueueBuilder.durable(RabbitTopologyConfig.controlQueueName(props.resolveWorkerId()))
                .autoDelete()
                .build();
    }

    // Qualified explicitly: three DirectExchange beans exist, so by-type injection is ambiguous.
    @Bean
    public Binding controlBinding(@Qualifier("controlExchange") DirectExchange controlExchange,
                                  WorkerProperties props) {
        return BindingBuilder.bind(controlQueue(props)).to(controlExchange).with(props.resolveWorkerId());
    }

    @Bean
    public SimpleRabbitListenerContainerFactory workerListenerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL); // ack only after confirmed result POST (FR-WRK-04)
        factory.setPrefetchCount(1);                        // one run at a time per session (FR-WRK-02)
        factory.setDefaultRequeueRejected(false);           // reject → dead-letter (FR-WRK-05)
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        return factory;
    }
}
