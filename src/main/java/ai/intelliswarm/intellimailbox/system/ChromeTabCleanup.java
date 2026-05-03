package ai.intelliswarm.intellimailbox.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Closes the IntelliMailbox UI tab(s) in the attached Chrome on shutdown,
 * so the user doesn't return to a stale "This site can't be reached"
 * tab next session. Uses Chrome's DevTools HTTP endpoint (port 9222) —
 * no Playwright dependency, so it works even if BrowserTool has already
 * been torn down.
 *
 * <p>Best-effort: a 1-second timeout, all errors swallowed. The Gmail
 * tab in the same window is intentionally left alone — the user may
 * still want to read their inbox there.
 */
@Component
public class ChromeTabCleanup {

    private static final Logger logger = LoggerFactory.getLogger(ChromeTabCleanup.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${swarmai.tools.browser.cdp-port:9222}")
    private int cdpPort;

    @Value("${server.port:8090}")
    private int uiPort;

    @EventListener(ContextClosedEvent.class)
    public void closeUiTabs() {
        String uiOrigin = "http://localhost:" + uiPort;
        String cdpBase  = "http://localhost:" + cdpPort;

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();

        try {
            HttpRequest list = HttpRequest.newBuilder()
                    .uri(URI.create(cdpBase + "/json"))
                    .timeout(Duration.ofSeconds(1))
                    .GET().build();
            HttpResponse<String> resp = http.send(list, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                logger.debug("CDP /json returned HTTP {} — skipping tab cleanup", resp.statusCode());
                return;
            }
            JsonNode targets = MAPPER.readTree(resp.body());
            int closed = 0;
            for (JsonNode t : targets) {
                String type = t.path("type").asText("");
                String url  = t.path("url").asText("");
                String id   = t.path("id").asText("");
                if (!"page".equals(type) || id.isEmpty()) continue;
                if (!url.startsWith(uiOrigin)) continue;
                try {
                    HttpRequest close = HttpRequest.newBuilder()
                            .uri(URI.create(cdpBase + "/json/close/" + id))
                            .timeout(Duration.ofSeconds(1))
                            .GET().build();
                    http.send(close, HttpResponse.BodyHandlers.discarding());
                    closed++;
                } catch (Throwable ignored) { /* per-tab best-effort */ }
            }
            if (closed > 0) {
                logger.info("Shutdown: closed {} IntelliMailbox UI tab(s) in attached Chrome", closed);
            }
        } catch (Throwable t) {
            logger.debug("Chrome tab cleanup skipped: {}", t.toString());
        }
    }
}
