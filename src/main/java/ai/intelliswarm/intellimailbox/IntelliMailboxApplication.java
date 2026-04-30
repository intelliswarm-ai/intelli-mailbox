package ai.intelliswarm.intellimailbox;

import ai.intelliswarm.intellimailbox.gmail.RealChromeLauncher;
import ai.intelliswarm.swarmai.tool.common.BrowserTool;
import ai.intelliswarm.swarmai.tool.common.BrowserToolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

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
@SpringBootApplication
public class IntelliMailboxApplication {

    private static final Logger logger = LoggerFactory.getLogger(IntelliMailboxApplication.class);

    public static void main(String[] args) {
        // Default profile dir if not overridden — separate from gmail-dashboard's so the two
        // products don't fight over the same Chrome session if both are on the same machine.
        Path defaultProfile = Paths.get(System.getProperty("user.home"),
                ".intelliswarm", "intellimailbox-chrome");
        setIfAbsent("swarmai.tools.browser.enabled", "true");
        setIfAbsent("swarmai.tools.browser.user-data-dir", defaultProfile.toString());
        setIfAbsent("swarmai.tools.browser.allowed-hosts", "google.com,gmail.com");

        // Privacy-by-default. SPRING_PROFILES_ACTIVE=openai-mini flips to gpt-4o-mini.
        setIfAbsent("spring.profiles.active", "ollama");

        SpringApplication.run(IntelliMailboxApplication.class, args);
    }

    /**
     * Run after Spring is up: bring up the attached Chrome, reconfigure BrowserTool,
     * pre-warm the Gmail tab, and open the IntelliMailbox UI in that same Chrome.
     */
    @Bean
    CommandLineRunner attachAndAnnounce(ObjectProvider<BrowserTool> browserToolProvider,
                                        ObjectProvider<BrowserToolProperties> browserPropsProvider) {
        return args -> {
            Path profileDir = Paths.get(
                    System.getProperty("swarmai.tools.browser.user-data-dir",
                            Paths.get(System.getProperty("user.home"),
                                    ".intelliswarm", "intellimailbox-chrome").toString()));

            RealChromeLauncher.LaunchResult chrome;
            try {
                chrome = RealChromeLauncher.launch(profileDir, 9222);
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

            // Pre-warm the inbox in a daemon thread — first navigate against an unsigned-in
            // tab parks on Google's auth screen for the full 30s timeout, blocking startup.
            Thread prewarm = new Thread(() -> {
                try {
                    browser.execute(java.util.Map.of("operation", "navigate",
                            "url", "https://mail.google.com/mail/u/0/#inbox"));
                } catch (Exception e) {
                    logger.debug("IntelliMailbox pre-warm navigate didn't complete: {}", e.getMessage());
                }
            }, "intellimailbox-prewarm");
            prewarm.setDaemon(true);
            prewarm.start();

            String uiUrl = "http://localhost:" + System.getProperty("server.port", "8090") + "/";
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

            openInAttachedChrome(uiUrl, 9222);
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
