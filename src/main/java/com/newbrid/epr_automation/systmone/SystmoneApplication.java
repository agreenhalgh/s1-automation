package com.newbrid.epr_automation.systmone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SystmoneApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(SystmoneApplication.class);
		// A worker drives java.awt.Robot for screen capture and mouse/keyboard input
		// (FR-SR-06, FR-JAB-08). SpringApplication defaults java.awt.headless to true,
		// which makes GraphicsEnvironment.isHeadless() report true even on a real Windows
		// desktop — RobotInput then never constructs a Robot, ScreenBackend reports itself
		// unavailable, and every IMAGE locator is skipped. Note this cannot be set from
		// application.yaml: configureHeadlessProperty() runs before spring.main.* is bound.
		app.setHeadless(false);
		app.run(args);
	}

}
