package com.newbrid.epr_automation.systmone.worker;

import com.newbrid.epr_automation.systmone.automation.jab.AccessBridge;
import com.newbrid.epr_automation.systmone.automation.jab.JabBackend;
import com.newbrid.epr_automation.systmone.automation.jab.JnaAccessBridge;
import com.newbrid.epr_automation.systmone.automation.screen.RobotInput;
import com.newbrid.epr_automation.systmone.automation.screen.ScreenBackend;
import com.newbrid.epr_automation.systmone.automation.screen.TemplateStore;
import com.newbrid.epr_automation.systmone.automation.window.NoopWindowControl;
import com.newbrid.epr_automation.systmone.automation.window.Win32WindowControl;
import com.newbrid.epr_automation.systmone.sdk.engine.WindowControl;
import com.newbrid.epr_automation.systmone.sdk.engine.BotEngine;
import com.newbrid.epr_automation.systmone.sdk.engine.ScreenshotSink;
import com.newbrid.epr_automation.systmone.sdk.locator.BackendRegistry;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
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

    @Bean
    public BotEngine botEngine(BackendRegistry registry, ScreenshotSink sink, WindowControl windows) {
        return new BotEngine(registry, sink, windows);
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
