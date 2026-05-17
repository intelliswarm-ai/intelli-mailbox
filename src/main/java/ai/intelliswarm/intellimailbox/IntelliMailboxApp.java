package ai.intelliswarm.intellimailbox;

import ai.intelliswarm.intellimailbox.gmail.RealChromeLauncher;
import ai.intelliswarm.intellimailbox.settings.SettingsStore;
import ai.intelliswarm.swarmai.tool.common.BrowserTool;
import ai.intelliswarm.swarmai.tool.common.BrowserToolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * IntelliMailbox — AI-preprocessed mailbox.
 *
 * <p>On startup:
 *   1. Launches your real Chrome with a dedicated CDP-attached profile.
 *   2. Reconfigures BrowserTool to attach to that Chrome.
 *   3. Pre-warms the Gmail inbox tab.
 *   4. Boots the embedded Tomcat web UI on :8090.
 *   5. Opens the IntelliMailbox UI in a new tab inside that same Chrome.
 *
 * <p>The UI queries {@code /api/inbox} which kicks off background enrichment;
 * results stream back over SSE to {@code /api/inbox/stream} as each email is
 * analyzed.
 */
/**
 * Component scan widened to {@code ai.intelliswarm} so framework beans like
 * {@code BrowserTool} (in {@code ai.intelliswarm.swarmai.tool.common}) get picked
 * up alongside this app's own beans.
 */
@SpringBootApplication(scanBasePackages = "ai.intelliswarm")
public class IntelliMailboxApp {

    private static final Logger logger = LoggerFactory.getLogger(IntelliMailboxApp.class);

    public static void main(String[] args) {
        // Single-instance guard. When a user double-clicks the icon and an
        // older instance is still bound to the configured port, Spring Boot's
        // Tomcat fails with "Port N was already in use" and the JVM exits —
        // surfacing as a generic launcher error to the user. Detect that here
        // and either re-open the existing UI or fail with a clear message.
        int port = resolvePreflightPort(args);
        ensureSingleInstance(port);

        // Default profile dir if not overridden — separate from gmail-dashboard's so the two
        // products don't fight over the same Chrome session if both are on the same machine.
        Path defaultProfile = Paths.get(System.getProperty("user.home"),
                ".intelliswarm", "intellimailbox-chrome");
        setIfAbsent("swarmai.tools.browser.enabled", "true");
        setIfAbsent("swarmai.tools.browser.user-data-dir", defaultProfile.toString());
        setIfAbsent("swarmai.tools.browser.allowed-hosts", "google.com,gmail.com");

        // Apply the user's persisted Settings-modal choices BEFORE the hardcoded
        // "ollama" default below, so a saved provider=openai actually wins.
        // setIfAbsent semantics inside the store keep -Dspring.profiles.active=… authoritative.
        SettingsStore.applyAsSystemProperties();

        // Privacy-by-default. SPRING_PROFILES_ACTIVE=openai-mini flips to gpt-4o-mini.
        setIfAbsent("spring.profiles.active", "ollama");

        SpringApplication.run(IntelliMailboxApp.class, args);
    }

    /**
     * Resolve the port the embedded server will try to bind, mirroring the
     * precedence Spring uses: {@code --server.port=…} CLI flag, then
     * {@code -Dserver.port=…} system property, then {@code SERVER_PORT}
     * env var, then the {@code 8090} default in application.yml.
     */
    private static int resolvePreflightPort(String[] args) {
        for (String a : args) {
            if (a == null) continue;
            if (a.startsWith("--server.port=")) {
                return parsePortOr(a.substring("--server.port=".length()), 8090);
            }
        }
        String sp = System.getProperty("server.port");
        if (sp != null && !sp.isBlank()) return parsePortOr(sp, 8090);
        String env = System.getenv("SERVER_PORT");
        if (env != null && !env.isBlank()) return parsePortOr(env, 8090);
        return 8090;
    }

    private static int parsePortOr(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    /**
     * If the configured port is already in use, probe whether the listener is
     * another IntelliMailbox instance via {@code GET /api/health}. If yes,
     * ask it to shut down via {@code POST /api/shutdown} and wait for the
     * port to free up before continuing — gives the user a clean "click icon
     * to relaunch" experience even when a previous JVM is still around.
     * If something else owns the port, exit non-zero with a clear message.
     */
    private static void ensureSingleInstance(int port) {
        if (isPortFree(port)) return;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build();
        boolean isOurInstance = false;
        try {
            HttpResponse<String> r = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/api/health"))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            isOurInstance = r.statusCode() / 100 == 2
                    && r.body() != null
                    && r.body().contains("\"ok\":true");
        } catch (Exception probeFailed) {
            // Port bound but not answering HTTP — treat as "not us".
        }

        if (!isOurInstance) {
            System.err.println();
            System.err.println("IntelliMailbox can't start: port " + port
                    + " is already in use by another application.");
            System.err.println("Either close that app, or set SERVER_PORT "
                    + "(e.g. SERVER_PORT=8091) and try again.");
            System.err.println();
            System.exit(2);
        }

        logger.info("IntelliMailbox is already running on port {} — asking it to shut down "
                + "so this launch can take over.", port);
        try {
            client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/api/shutdown"))
                            .timeout(Duration.ofSeconds(3))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            logger.warn("Couldn't reach the existing instance's /api/shutdown: {}",
                    e.getMessage());
        }

        // ShutdownController has a 10s hard deadline; give it a little headroom.
        long deadline = System.currentTimeMillis() + 12_000;
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(400); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); break;
            }
            if (isPortFree(port)) {
                logger.info("Previous IntelliMailbox stopped — continuing startup.");
                return;
            }
        }
        System.err.println();
        System.err.println("Couldn't replace the running IntelliMailbox: the old instance "
                + "didn't release port " + port + " within 12 seconds.");
        System.err.println("Open the existing app at http://localhost:" + port + "/ "
                + "or end the running javaw.exe / java process manually, then retry.");
        System.err.println();
        System.exit(2);
    }

    /**
     * Try to grab the port for a moment. Free ⇒ {@code true}; bound by
     * something ⇒ {@code false}. Cheaper and more reliable than a TCP connect
     * probe (which can be coloured by firewalls / loopback peculiarities).
     */
    private static boolean isPortFree(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            s.setReuseAddress(false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Run after Spring is up: bring up the attached Chrome, reconfigure BrowserTool,
     * pre-warm the Gmail tab, and open the IntelliMailbox UI in that same Chrome.
     */
    @Bean
    CommandLineRunner attachAndAnnounce(ObjectProvider<BrowserTool> browserToolProvider,
                                        ObjectProvider<BrowserToolProperties> browserPropsProvider,
                                        ObjectProvider<ai.intelliswarm.intellimailbox.system.ChromeSessionService> sessionProvider) {
        return args -> {
            Path profileDir = Paths.get(
                    System.getProperty("swarmai.tools.browser.user-data-dir",
                            Paths.get(System.getProperty("user.home"),
                                    ".intelliswarm", "intellimailbox-chrome").toString()));

            String uiUrl = "http://localhost:" + System.getProperty("server.port", "8090") + "/";

            RealChromeLauncher.LaunchResult chrome;
            try {
                // Pass uiUrl as the initial URL so Chrome opens with the IntelliMailbox
                // UI in tab 1 instead of its default new-tab page (which would otherwise
                // sit there as an orphaned blank tab for the lifetime of the session,
                // and remain in the user's session restore after Quit).
                chrome = RealChromeLauncher.launch(profileDir, 9222, uiUrl);
            } catch (IllegalStateException e) {
                logger.error("IntelliMailbox couldn't launch real Chrome: {}", e.getMessage());
                return;
            }

            BrowserTool browser = browserToolProvider.getIfAvailable();
            BrowserToolProperties browserProps = browserPropsProvider.getIfAvailable();
            if (browser == null || browserProps == null) {
                logger.error("IntelliMailbox: BrowserTool not active. "
                        + "Set swarmai.tools.browser.enabled=true.");
                return;
            }
            browserProps.setMode("attach");
            browserProps.setCdpUrl(chrome.cdpUrl());
            browser.shutdown(); // discard cached state so next call re-inits in attach mode

            // Hand the Chrome handle + profile path to the session service so
            // /api/chrome/logout can wipe credentials on demand.
            ai.intelliswarm.intellimailbox.system.ChromeSessionService session =
                    sessionProvider.getIfAvailable();
            if (session != null) {
                session.register(chrome.process(), profileDir, chrome.cdpUrl());
            }

            // No pre-warm navigate. We used to fire one here in a daemon
            // thread, but BrowserTool's `navigate` op ignores `timeout_ms`
            // (swarmai issue) — so it always sat on Playwright's default
            // 30-second NETWORKIDLE timeout. Worse, BrowserTool is fully
            // synchronized, so during those 30s ANY OTHER browser-tool call
            // (like the welcome-screen diagnostics' Gmail probe) blocked
            // behind the prewarm lock. Net effect: the user saw "Checking
            // your system…" stuck for half a minute on every cold start.
            // Skipping the prewarm trades nothing — the first refreshInbox()
            // does the navigate on demand via ensureOnInboxListing() anyway.

            logger.info("");
            logger.info("=".repeat(80));
            logger.info("  IntelliMailbox is up");
            logger.info("=".repeat(80));
            logger.info("  Open: {}", uiUrl);
            logger.info("  Chrome (attached): {}", chrome.cdpUrl());
            logger.info("  Profile dir:       {}", profileDir);
            logger.info("");
            logger.info("  Sign in to Gmail in the Chrome window that opened, then watch the");
            logger.info("  inbox auto-populate — each email is analyzed locally as it loads.");
            logger.info("=".repeat(80));

            // Only spawn a new tab when Chrome was already running and we attached
            // to it — in that case Chrome wasn't told about the UI URL at launch.
            // For a fresh launch the URL was passed as the initial positional arg
            // and is already loaded in tab 1, so opening another tab would dupe it.
            if (chrome.wasAlreadyRunning()) {
                openInAttachedChrome(uiUrl, 9222);
            }
        };
    }

    /**
     * Use the attached Chrome's CDP HTTP API to open the UI in a new tab in the same window
     * that holds the Gmail tab. More reliable than Java's Desktop API on WSL/headless setups.
     */
    private static void openInAttachedChrome(String url, int cdpPort) {
        URI cdpEndpoint = URI.create("http://localhost:" + cdpPort + "/json/new?" + url);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build();
        try {
            HttpRequest put = HttpRequest.newBuilder()
                    .uri(cdpEndpoint)
                    .method("PUT", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(3)).build();
            HttpResponse<Void> resp = client.send(put, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() / 100 == 2) {
                logger.info("IntelliMailbox: opened new tab in attached Chrome at {}", url);
                return;
            }
            if (resp.statusCode() == 405) {
                HttpRequest get = HttpRequest.newBuilder()
                        .uri(cdpEndpoint).GET().timeout(Duration.ofSeconds(3)).build();
                HttpResponse<Void> resp2 = client.send(get, HttpResponse.BodyHandlers.discarding());
                if (resp2.statusCode() / 100 == 2) {
                    logger.info("IntelliMailbox: opened new tab in attached Chrome at {}", url);
                    return;
                }
            }
            logger.debug("IntelliMailbox: CDP /json/new returned HTTP {} — open {} manually",
                    resp.statusCode(), url);
        } catch (Exception e) {
            logger.debug("IntelliMailbox: CDP new-tab failed ({}) — open {} manually",
                    e.getMessage(), url);
        }
    }

    private static void setIfAbsent(String k, String v) {
        if (System.getProperty(k) == null) System.setProperty(k, v);
    }
}
