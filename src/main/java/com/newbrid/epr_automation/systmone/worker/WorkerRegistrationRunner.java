package com.newbrid.epr_automation.systmone.worker;

import com.newbrid.epr_automation.systmone.automation.jab.AccessBridge;
import com.newbrid.epr_automation.systmone.automation.screen.RobotInput;
import com.newbrid.epr_automation.systmone.common.dto.WorkerRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;

/** Registers this worker and its capabilities with the control plane on startup (FR-WRK-08). */
@Component
@Profile("worker")
public class WorkerRegistrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkerRegistrationRunner.class);

    private final ControlPlaneClient client;
    private final WorkerProperties props;
    private final AccessBridge bridge;
    private final RobotInput robot;

    public WorkerRegistrationRunner(ControlPlaneClient client, WorkerProperties props,
                                    AccessBridge bridge, RobotInput robot) {
        this.client = client;
        this.props = props;
        this.bridge = bridge;
        this.robot = robot;
    }

    @Override
    public void run(ApplicationArguments args) {
        int w = 0, h = 0;
        if (!GraphicsEnvironment.isHeadless()) {
            var size = Toolkit.getDefaultToolkit().getScreenSize();
            w = size.width;
            h = size.height;
        }
        List<String> adapters = new ArrayList<>();
        if (bridge.isAvailable()) adapters.add("JAB");
        if (robot.isAvailable()) adapters.add("SCREEN");

        List<String> capabilities = new ArrayList<>();
        if (props.getCapabilityTag() != null && !props.getCapabilityTag().isBlank()) {
            capabilities.add(props.getCapabilityTag());
        }

        WorkerRegistration reg = new WorkerRegistration(
                client.workerId(), hostName(), props.getS1Version(), w, h, capabilities, adapters);
        try {
            client.register(reg);
            log.info("Registered worker {} with control plane (adapters={})", client.workerId(), adapters);
        } catch (Exception e) {
            log.warn("Worker registration failed (will still consume jobs): {}", e.toString());
        }
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
