package ai.intelliswarm.intellimailbox.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Backup quit path for users who didn't notice the system-tray icon.
 *
 * <pre>
 *   POST /api/shutdown    → graceful Spring shutdown ~1s later
 * </pre>
 *
 * <p>Why a delay? We want the HTTP response to flush back to the browser
 * BEFORE the server stops accepting connections — otherwise the user sees
 * "connection refused" instead of the friendly "Intelli-mailbox stopped"
 * confirmation page the UI shows after this returns.
 */
@RestController
@RequestMapping("/api")
public class ShutdownController {

    private static final Logger logger = LoggerFactory.getLogger(ShutdownController.class);

    private final ConfigurableApplicationContext context;
    private final IdleShutdownManager idleShutdown;

    public ShutdownController(ConfigurableApplicationContext context,
                              IdleShutdownManager idleShutdown) {
        this.context = context;
        this.idleShutdown = idleShutdown;
    }

    /**
     * Hint endpoint hit by the browser's {@code beforeunload} sendBeacon.
     * The browser is closing or refreshing — tell {@link IdleShutdownManager}
     * so it can use its short grace period if no client reconnects. Refresh
     * still works because the new page's SSE will reconnect within the
     * short window. Pure no-op response since sendBeacon ignores the body.
     */
    @PostMapping("/shutdown-hint")
    public Map<String, Object> shutdownHint() {
        idleShutdown.hintWindowClosing();
        return Map.of("ok", true);
    }

    /** Hard deadline for the entire shutdown sequence. If clean exit hasn't
     *  finished by then, the watchdog calls {@link Runtime#halt} to terminate
     *  the JVM unconditionally. We've seen Spring's graceful Tomcat shutdown
     *  hang past its own 30s timeout, leaving the JVM half-alive and holding
     *  port 8090 — which makes the next launch fail with "port already in use".
     *  10s after the request is plenty: 1s response delay + ~1-2s real teardown
     *  with SSE emitters closed eagerly by InboxController.@PreDestroy. */
    private static final long SHUTDOWN_DEADLINE_MS = 10_000;

    @PostMapping("/shutdown")
    public Map<String, Object> shutdown() {
        logger.info("Shutdown requested via /api/shutdown — exiting in 1s, hard-deadline {}s",
                SHUTDOWN_DEADLINE_MS / 1000);

        // Watchdog: regardless of what happens with Spring's exit, force-kill
        // the JVM at the deadline. Daemon thread + Runtime.halt skips any
        // remaining shutdown hooks (which is the point — they're what's hanging).
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(SHUTDOWN_DEADLINE_MS); } catch (InterruptedException ie) { return; }
            logger.warn("Shutdown watchdog: deadline reached — forcing Runtime.halt(2)");
            Runtime.getRuntime().halt(2);
        }, "intellimailbox-shutdown-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        // Off-thread, deferred so the HTTP response makes it back to the user.
        new Thread(() -> {
            try { Thread.sleep(1_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            try {
                int code = SpringApplication.exit(context, () -> 0);
                System.exit(code);
            } catch (Throwable t) {
                logger.warn("Shutdown errored: {}", t.toString());
                System.exit(1);
            }
        }, "intellimailbox-shutdown").start();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("message", "Intelli-mailbox is stopping. You can close this tab.");
        return resp;
    }
}
