package com.newbrid.epr_automation.systmone.automation.screen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.DisplayMode;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;

/**
 * OS-level mouse/keyboard driving and screen capture via {@link java.awt.Robot}
 * (FR-SR-06, FR-JAB-08). Degrades to unavailable in headless environments so the
 * engine can skip screen-recognition steps rather than crash.
 */
public class RobotInput {

    private static final Logger log = LoggerFactory.getLogger(RobotInput.class);

    private final Robot robot;

    public RobotInput() {
        Robot r = null;
        if (GraphicsEnvironment.isHeadless()) {
            // Silently degrading here disables every IMAGE locator for the whole process,
            // so say why — on a real desktop this almost always means the JVM was started
            // with java.awt.headless=true rather than that no display exists.
            log.warn("java.awt.headless={} — screen recognition and Robot input are disabled. "
                            + "IMAGE locators will be skipped and screenshots will not be captured. "
                            + "Start the worker with -Djava.awt.headless=false.",
                    System.getProperty("java.awt.headless"));
        } else {
            try {
                r = new Robot();
                r.setAutoDelay(20);
            } catch (AWTException | SecurityException e) {
                log.warn("Robot unavailable: {}", e.toString());
            }
        }
        this.robot = r;
        if (r != null) logDisplayGeometry();
    }

    public boolean isAvailable() {
        return robot != null;
    }

    /**
     * Desktop scale factor: 1.0 unscaled, 2.0 at 200% display scaling, and so on.
     *
     * <p>This is the single most common reason a template stops matching. Robot captures
     * the <em>logical</em> desktop, while Snipping Tool, Print Screen and every other
     * native capture tool crop <em>physical</em> pixels — so on a scaled display a
     * hand-cropped template is exactly this many times too large, and NCC, which has no
     * scale invariance, scores it near zero.</p>
     */
    public double uiScale() {
        if (robot == null) return 1.0;
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration()
                    .getDefaultTransform()
                    .getScaleX();
        } catch (Throwable t) {
            log.debug("Could not read the desktop scale factor: {}", t.toString());
            return 1.0;
        }
    }

    /** Logged once at startup: the geometry every image locator is implicitly measured against. */
    private void logDisplayGeometry() {
        try {
            Dimension logical = Toolkit.getDefaultToolkit().getScreenSize();
            double scale = uiScale();
            DisplayMode mode = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDisplayMode();
            if (scale > 1.0 + 1e-6) {
                log.info("Display: {}x{} physical, captured as {}x{} at {}x scaling. "
                                + "Templates cropped with a native screenshot tool are {}x too "
                                + "large for this capture and are rescaled automatically; crop them "
                                + "from a worker failure screenshot to avoid the guess.",
                        mode.getWidth(), mode.getHeight(), logical.width, logical.height,
                        scale, scale);
            } else {
                log.info("Display: {}x{}, no desktop scaling", logical.width, logical.height);
            }
        } catch (Throwable t) {
            log.debug("Could not report display geometry: {}", t.toString());
        }
    }

    private void requireRobot() {
        if (robot == null) throw new IllegalStateException("Robot input is not available (headless environment)");
    }

    public BufferedImage captureScreen() {
        if (robot == null) return null;
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        return robot.createScreenCapture(screen);
    }

    public void move(int x, int y) {
        requireRobot();
        robot.mouseMove(x, y);
    }

    public void click(int x, int y) {
        move(x, y);
        press(InputEvent.BUTTON1_DOWN_MASK);
        release(InputEvent.BUTTON1_DOWN_MASK);
    }

    public void doubleClick(int x, int y) {
        click(x, y);
        click(x, y);
    }

    public void rightClick(int x, int y) {
        move(x, y);
        press(InputEvent.BUTTON3_DOWN_MASK);
        release(InputEvent.BUTTON3_DOWN_MASK);
    }

    public void hover(int x, int y) {
        move(x, y);
    }

    public void drag(Point from, Point to) {
        requireRobot();
        move(from.x, from.y);
        press(InputEvent.BUTTON1_DOWN_MASK);
        move(to.x, to.y);
        release(InputEvent.BUTTON1_DOWN_MASK);
    }

    private void press(int mask) { requireRobot(); robot.mousePress(mask); }
    private void release(int mask) { requireRobot(); robot.mouseRelease(mask); }

    public Point mouseLocation() {
        return MouseInfo.getPointerInfo().getLocation();
    }

    /** Type literal text, one character at a time. */
    public void type(String text) {
        requireRobot();
        if (text == null) return;
        for (char c : text.toCharArray()) typeChar(c);
    }

    /** Send a key chord like {@code "CTRL+S"}, {@code "ENTER"}, {@code "ALT+F4"}. */
    public void keys(String combo) {
        requireRobot();
        if (combo == null || combo.isBlank()) return;
        String[] parts = combo.toUpperCase().split("\\+");
        int[] codes = new int[parts.length];
        for (int i = 0; i < parts.length; i++) codes[i] = keyCode(parts[i].trim());
        for (int c : codes) robot.keyPress(c);
        for (int i = codes.length - 1; i >= 0; i--) robot.keyRelease(codes[i]);
    }

    private void typeChar(char c) {
        boolean shift = Character.isUpperCase(c) || "~!@#$%^&*()_+{}|:\"<>?".indexOf(c) >= 0;
        int code = KeyEvent.getExtendedKeyCodeForChar(c);
        if (code == KeyEvent.VK_UNDEFINED) {
            log.debug("No key code for char '{}', skipping", c);
            return;
        }
        if (shift) robot.keyPress(KeyEvent.VK_SHIFT);
        robot.keyPress(code);
        robot.keyRelease(code);
        if (shift) robot.keyRelease(KeyEvent.VK_SHIFT);
    }

    private static int keyCode(String name) {
        return switch (name) {
            case "CTRL", "CONTROL" -> KeyEvent.VK_CONTROL;
            case "ALT" -> KeyEvent.VK_ALT;
            case "SHIFT" -> KeyEvent.VK_SHIFT;
            case "ENTER", "RETURN" -> KeyEvent.VK_ENTER;
            case "TAB" -> KeyEvent.VK_TAB;
            case "ESC", "ESCAPE" -> KeyEvent.VK_ESCAPE;
            case "SPACE" -> KeyEvent.VK_SPACE;
            case "DELETE", "DEL" -> KeyEvent.VK_DELETE;
            case "BACKSPACE" -> KeyEvent.VK_BACK_SPACE;
            case "HOME" -> KeyEvent.VK_HOME;
            case "END" -> KeyEvent.VK_END;
            case "F1" -> KeyEvent.VK_F1;
            case "F2" -> KeyEvent.VK_F2;
            case "F3" -> KeyEvent.VK_F3;
            case "F4" -> KeyEvent.VK_F4;
            default -> {
                if (name.length() == 1) yield KeyEvent.getExtendedKeyCodeForChar(name.charAt(0));
                throw new IllegalArgumentException("Unknown key: " + name);
            }
        };
    }
}
