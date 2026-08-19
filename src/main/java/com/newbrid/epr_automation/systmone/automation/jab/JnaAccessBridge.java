package com.newbrid.epr_automation.systmone.automation.jab;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser.MSG;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Real {@link AccessBridge} backed by {@code WindowsAccessBridge-64.dll} via
 * {@link WindowsAccessBridgeLib}. Loads lazily and guards every native call so a
 * missing/misconfigured Access Bridge downgrades to "unavailable" rather than crashing
 * (the framework then relies on screen recognition — R1/R3 mitigations).
 *
 * <h4>Why every call runs on one dedicated thread</h4>
 * The Access Bridge is a message-driven, thread-affine API. {@code Windows_run()} announces
 * this client by broadcasting a Windows message; each target JVM answers with a message that
 * only lands if the <em>calling thread pumps its message queue</em>. Without a pump the
 * bridge's table of known JVMs stays empty and {@code isJavaWindow} answers FALSE for every
 * window — measured on a live host: not-a-Java-window before pumping, resolved contexts for
 * both a 32-bit and a 64-bit target JVM after. The bridge also keys its state to the thread
 * that called {@code Windows_run()}, so calls from a worker's AMQP listener thread would not
 * see it either. This class therefore owns a single daemon thread that runs
 * {@code Windows_run()}, pumps continuously, and executes every native call; public methods
 * hand work to it and wait for the result.
 *
 * <p>Note the bridge is <em>not</em> limited to same-bitness targets: the 64-bit client DLL
 * resolves contexts in a 32-bit JVM (verified against SystmOne on a 32-bit JRE 11).</p>
 */
public class JnaAccessBridge implements AccessBridge {

    private static final Logger log = LoggerFactory.getLogger(JnaAccessBridge.class);

    /** {@code PeekMessage} wRemoveMsg: remove the message from the queue. */
    private static final int PM_REMOVE = 1;

    /**
     * How long to pump before declaring the bridge ready. JVM discovery replies arrive
     * asynchronously, so a client that queries immediately sees no Java windows at all.
     */
    private static final long SETTLE_MS = 2_000;

    /** Cap on how long a caller waits for the bridge thread, so a wedged call cannot hang a run. */
    private static final long CALL_TIMEOUT_MS = 5_000;

    private final BlockingQueue<FutureTask<?>> calls = new LinkedBlockingQueue<>();
    private final CountDownLatch ready = new CountDownLatch(1);
    private final Thread bridgeThread;

    /** Written once on the bridge thread before {@link #ready} opens. */
    private volatile WindowsAccessBridgeLib lib;
    private volatile boolean available;

    public JnaAccessBridge() {
        if (!Platform.isWindows()) {
            log.info("Not a Windows host — Java Access Bridge disabled; screen recognition only");
            this.bridgeThread = null;
            return;
        }
        this.bridgeThread = new Thread(this::serve, "jab-bridge");
        this.bridgeThread.setDaemon(true);
        this.bridgeThread.start();
        try {
            // Initialisation includes the settle pump; bounded so a broken bridge cannot
            // stall worker startup.
            if (!ready.await(SETTLE_MS + CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                log.warn("Access Bridge initialisation did not complete in time; treating as unavailable");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    // =====================================================================
    // Bridge thread: owns the DLL, pumps messages, runs every native call
    // =====================================================================

    private void serve() {
        try {
            lib = Native.load("windowsaccessbridge-64", WindowsAccessBridgeLib.class);
            lib.Windows_run();
            available = true;
            log.info("Windows Access Bridge initialised on thread {}", Thread.currentThread().getName());
        } catch (Throwable t) {
            log.warn("Windows Access Bridge unavailable ({}). "
                    + "Ensure the JRE has 'jabswitch -enable' and the DLL is on the path. "
                    + "Falling back to screen recognition.", t.toString());
            ready.countDown();
            return;
        }

        MSG msg = new MSG();
        long settleUntil = System.currentTimeMillis() + SETTLE_MS;
        while (System.currentTimeMillis() < settleUntil) {
            pump(msg);
            sleep(10);
        }
        ready.countDown();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                FutureTask<?> task = calls.poll(10, TimeUnit.MILLISECONDS);
                if (task != null) task.run();
                pump(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                log.debug("Access Bridge thread caught {}", t.toString());
            }
        }
    }

    private static void pump(MSG msg) {
        while (User32.INSTANCE.PeekMessage(msg, null, 0, 0, PM_REMOVE)) {
            User32.INSTANCE.TranslateMessage(msg);
            User32.INSTANCE.DispatchMessage(msg);
        }
    }

    /** Runs {@code op} on the bridge thread, returning {@code fallback} on failure or timeout. */
    private <T> T onBridgeThread(String what, Function<WindowsAccessBridgeLib, T> op, T fallback) {
        if (!available) return fallback;
        if (Thread.currentThread() == bridgeThread) return op.apply(lib);   // reentrant call

        FutureTask<T> task = new FutureTask<>(() -> op.apply(lib));
        calls.add(task);
        try {
            return task.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(false);
            log.warn("Access Bridge call {} timed out after {}ms", what, CALL_TIMEOUT_MS);
            return fallback;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception e) {
            log.debug("Access Bridge call {} failed: {}", what, e.toString());
            return fallback;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =====================================================================
    // AccessBridge API
    // =====================================================================

    @Override
    public List<AcRef> roots(String windowTitleContains) {
        String needle = windowTitleContains == null ? "" : windowTitleContains.toLowerCase();
        // The whole enumeration runs on the bridge thread: isJavaWindow and
        // getAccessibleContextFromHWND are only valid there.
        return onBridgeThread("roots", l -> {
            List<AcRef> result = new ArrayList<>();
            try {
                User32.INSTANCE.EnumWindows((HWND hwnd, com.sun.jna.Pointer data) -> {
                    char[] buf = new char[512];
                    User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
                    String title = Native.toString(buf);
                    if (title != null && !title.isBlank() && title.toLowerCase().contains(needle)) {
                        try {
                            com.sun.jna.Pointer p = hwnd.getPointer();
                            if (l.isJavaWindow(p)) {
                                IntByReference vm = new IntByReference();
                                LongByReference ac = new LongByReference();
                                if (l.getAccessibleContextFromHWND(p, vm, ac)) {
                                    result.add(new AcRef(vm.getValue(), ac.getValue()));
                                } else {
                                    log.debug("Window '{}' is a Java window but yielded no context", title);
                                }
                            } else {
                                log.trace("Window '{}' is not a Java window", title);
                            }
                        } catch (Throwable t) {
                            log.debug("Probe of window '{}' failed: {}", title, t.toString());
                        }
                    }
                    return true;
                }, null);
            } catch (Throwable t) {
                log.warn("Window enumeration failed: {}", t.toString());
            }
            if (result.isEmpty()) {
                log.debug("No Java window matched '{}'", windowTitleContains);
            }
            return result;
        }, List.of());
    }

    @Override
    public AccessibleInfo info(AcRef ref) {
        if (ref == null) return null;
        return onBridgeThread("getAccessibleContextInfo", l -> {
            try {
                WindowsAccessBridgeLib.AccessibleContextInfo i = new WindowsAccessBridgeLib.AccessibleContextInfo();
                if (!l.getAccessibleContextInfo(ref.vmId(), ref.ac(), i)) return null;
                return new AccessibleInfo(
                        Native.toString(i.role),
                        Native.toString(i.name),
                        Native.toString(i.description),
                        Native.toString(i.states),
                        i.x, i.y, i.width, i.height,
                        i.childrenCount, i.indexInParent);
            } catch (Throwable t) {
                log.debug("getAccessibleContextInfo failed: {}", t.toString());
                return null;
            }
        }, null);
    }

    @Override
    public AcRef child(AcRef ref, int index) {
        if (ref == null) return null;
        return onBridgeThread("getAccessibleChildFromContext", l -> {
            try {
                long c = l.getAccessibleChildFromContext(ref.vmId(), ref.ac(), index);
                return c == 0 ? null : new AcRef(ref.vmId(), c);
            } catch (Throwable t) {
                return null;
            }
        }, null);
    }

    @Override
    public boolean doAction(AcRef ref, String action) {
        if (ref == null) return false;
        return onBridgeThread("doAccessibleActions", l -> {
            try {
                WindowsAccessBridgeLib.AccessibleActionsToDo todo = new WindowsAccessBridgeLib.AccessibleActionsToDo();
                todo.actionsCount = 1;
                char[] name = todo.actions[0].name;
                char[] src = action.toCharArray();
                System.arraycopy(src, 0, name, 0, Math.min(src.length, name.length - 1));
                todo.write();
                IntByReference failure = new IntByReference();
                return l.doAccessibleActions(ref.vmId(), ref.ac(), todo, failure);
            } catch (Throwable t) {
                log.debug("doAccessibleActions('{}') failed: {}", action, t.toString());
                return false;
            }
        }, false);
    }

    @Override
    public boolean setText(AcRef ref, String text) {
        if (ref == null) return false;
        return onBridgeThread("setTextContents", l -> {
            try {
                char[] wide = (text == null ? "" : text + "\0").toCharArray();
                return l.setTextContents(ref.vmId(), ref.ac(), wide);
            } catch (Throwable t) {
                log.debug("setTextContents failed: {}", t.toString());
                return false;
            }
        }, false);
    }

    @Override
    public String getText(AcRef ref) {
        if (ref == null) return null;
        return onBridgeThread("getAccessibleText", l -> {
            try {
                WindowsAccessBridgeLib.AccessibleTextInfo ti = new WindowsAccessBridgeLib.AccessibleTextInfo();
                if (!l.getAccessibleTextInfo(ref.vmId(), ref.ac(), ti, 0, 0)) return null;
                int count = Math.max(0, Math.min(ti.charCount, WindowsAccessBridgeLib.MAX_STRING_SIZE - 1));
                if (count == 0) return "";
                char[] buf = new char[count + 1];
                if (!l.getAccessibleTextRange(ref.vmId(), ref.ac(), 0, count - 1, buf, (short) buf.length)) {
                    return null;
                }
                return Native.toString(buf);
            } catch (Throwable t) {
                log.debug("getAccessibleText failed: {}", t.toString());
                return null;
            }
        }, null);
    }

    @Override
    public void release(AcRef ref) {
        if (ref == null) return;
        onBridgeThread("releaseJavaObject", l -> {
            try {
                l.releaseJavaObject(ref.vmId(), ref.ac());
            } catch (Throwable ignored) {
                // best-effort
            }
            return null;
        }, null);
    }
}
