package ai.intelliswarm.intellimailbox.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto-shutdown when no browser is watching.
 *
 * <p>When a non-tech user closes the IntelliMailbox window they expect the
 * app to stop, exactly like a desktop app. But this is a web app served on
 * localhost — the JVM has no direct way to know the window has closed.
 *
 * <p>Heuristic that works in practice: <b>track SSE-subscriber count</b>
 * (the {@code /api/inbox/stream} connection that every visible browser
 * holds open). When the count drops to zero, start a grace-period timer
 * (default 15 s). If a new subscriber connects within the grace window
 * (page refresh, tab reopen, network blip), cancel the timer. Otherwise
 * shut down cleanly via {@link SpringApplication#exit}.
 *
 * <p>Why a grace period? Killing the JVM on the first disconnect would
 * make every {@code Ctrl+R} reload kill the app — actively hostile UX.
 * 15 seconds is long enough to swallow refreshes / tab-reopen and short
 * enough that a real "closed it and walked away" user gets cleanup.
 *
 * <p>Configurable via {@code intellimailbox.idle-shutdown.enabled} and
 * {@code intellimailbox.idle-shutdown.timeout-seconds}.
 */
@Component
public class IdleShutdownManager {

    private static final Logger logger = LoggerFactory.getLogger(IdleShutdownManager.class);

    private final ConfigurableApplicationContext context;
    private final AtomicInteger activeClients = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "intellimailbox-idle-shutdown");
        t.setDaemon(true);
        return t;
    });

    /** When true (default), the app shuts down once no browser is connected. */
    @Value("${intellimailbox.idle-shutdown.enabled:true}")
    private boolean enabled;

    /** Grace-period seconds between "last browser left" and "shut down". */
    @Value("${intellimailbox.idle-shutdown.timeout-seconds:15}")
    private int timeoutSeconds;

    private volatile ScheduledFuture<?> pendingShutdown;

    public IdleShutdownManager(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /** Called from the SSE controller every time a new browser opens the stream. */
    public synchronized void clientConnected() {
        int n = activeClients.incrementAndGet();
        if (pendingShutdown != null) {
            pendingShutdown.cancel(false);
            pendingShutdown = null;
            logger.info("Idle-shutdown cancelled — browser reconnected (active={})", n);
        }
    }

    /** Called from the SSE controller every time a browser disconnects. */
    public synchronized void clientDisconnected() {
        int n = activeClients.decrementAndGet();
        if (n < 0) {
            // Defensive: clamp at 0 so a double-call doesn't make the count negative.
            activeClients.set(0);
            n = 0;
        }
        if (n == 0 && enabled) {
            scheduleShutdown();
        } else {
            logger.debug("Browser disconnected, {} still active", n);
        }
    }

    private synchronized void scheduleShutdown() {
        if (pendingShutdown != null) return;
        int grace = effectiveGraceSeconds();
        logger.info("Last browser disconnected — shutting down in {} s if no client reconnects", grace);
        pendingShutdown = scheduler.schedule(this::doShutdown, grace, TimeUnit.SECONDS);
    }

    /** Hard deadline. Same rationale as ShutdownController — the JVM has
     *  hung past Spring's own 30s graceful timeout in the past, holding
     *  port 8090. Force-halt if we don't exit cleanly in time. */
    private static final long SHUTDOWN_DEADLINE_MS = 10_000;

    private void doShutdown() {
        synchronized (this) {
            // Re-check inside the synchronized block to avoid a race between
            // "scheduler fired" and "a new client just connected".
            if (activeClients.get() > 0) {
                logger.info("Idle-shutdown timer fired, but a client reconnected meanwhile — staying up");
                pendingShutdown = null;
                return;
            }
        }
        logger.info("Idle-shutdown timer fired with no clients — exiting (hard-deadline {}s)",
                SHUTDOWN_DEADLINE_MS / 1000);

        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(SHUTDOWN_DEADLINE_MS); } catch (InterruptedException ie) { return; }
            logger.warn("Idle-shutdown watchdog: deadline reached — forcing Runtime.halt(2)");
            Runtime.getRuntime().halt(2);
        }, "intellimailbox-idle-shutdown-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        try {
            int code = SpringApplication.exit(context, () -> 0);
            System.exit(code);
        } catch (Throwable t) {
            logger.warn("Idle-shutdown errored: {}", t.toString());
            System.exit(1);
        }
    }

    private volatile long recentHintAtMs = 0;

    /**
     * Called by the JS {@code beforeunload} sendBeacon — a hint that the
     * user is actively closing the window (vs. just refreshing). When a hint
     * is received, the next {@link #clientDisconnected()} that empties the
     * client pool uses a SHORT grace period instead of the full 15 s.
     * Refresh-then-reconnect still works because the new client's
     * {@link #clientConnected()} will fire within the short window and
     * cancel the timer.
     */
    public synchronized void hintWindowClosing() {
        recentHintAtMs = System.currentTimeMillis();
        logger.debug("Window-closing hint received");
    }

    /** Effective grace period for the upcoming shutdown. */
    private synchronized int effectiveGraceSeconds() {
        long sinceHintMs = System.currentTimeMillis() - recentHintAtMs;
        return (sinceHintMs < 5_000) ? 2 : timeoutSeconds;
    }

    /** Test hook / introspection. */
    public int activeClientCount() {
        return activeClients.get();
    }
}
