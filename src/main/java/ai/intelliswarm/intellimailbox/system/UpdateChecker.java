package ai.intelliswarm.intellimailbox.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.intelliswarm.intellimailbox.IntelliMailboxApp;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polls GitHub releases for the latest published Intelli-mailbox version and
 * compares it to the running jar's {@code project.version}. Result is exposed
 * via {@code /api/version} so the UI can surface an "update available" banner
 * with a link to the release page.
 *
 * <p>Why an in-app banner: the MSI / DMG / DEB installer route doesn't run an
 * auto-update agent. Without a poll-based prompt, users only learn about
 * security fixes when they happen to look at the GitHub page. The banner is
 * non-intrusive — single dismissible line, no modal, no forced restart — but
 * makes new releases discoverable.
 *
 * <p>Frequency: one check on startup (after a short delay so it doesn't
 * compete with first-paint), then every 6 hours. Failures are silent.
 */
@Service
public class UpdateChecker {

    private static final Logger logger = LoggerFactory.getLogger(UpdateChecker.class);

    private static final String RELEASES_URL =
            "https://api.github.com/repos/intelliswarm-ai/intelli-mailbox/releases/latest";

    private final String currentVersion;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "intellimailbox-update-checker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<VersionInfo> latest = new AtomicReference<>(null);

    public UpdateChecker() {
        // Spring Boot's maven plugin writes Implementation-Version into the
        // packaged jar's MANIFEST.MF, derived from pom.xml's <version>.
        // Reading it from the class's package keeps the version single-sourced
        // in the pom — no duplication, no resource-filtering plumbing.
        String fromManifest = IntelliMailboxApp.class.getPackage().getImplementationVersion();
        String fromEnv = System.getenv("INTELLIMAILBOX_VERSION");
        if (fromEnv != null && !fromEnv.isBlank()) {
            this.currentVersion = fromEnv.trim();
        } else if (fromManifest != null && !fromManifest.isBlank()) {
            this.currentVersion = fromManifest.trim();
        } else {
            // IDE / `mvn exec:java` runs — no jar manifest. Surfaces as "dev"
            // in the UI; intentionally non-numeric so isNewer always returns
            // true for any actual release, keeping the banner visible to
            // developers running against an outdated working tree.
            this.currentVersion = "dev";
        }
    }

    @PostConstruct
    void start() {
        // Short initial delay (used to be 30s to stay out of first-paint),
        // bumped down because the UI's update banner fetches /api/version
        // once at startup — if the GitHub poll hasn't fired by then the
        // banner permanently sees "no update available" for the rest of
        // the session. 3s is plenty of headroom past app boot, and the
        // GitHub call itself is async + non-blocking.
        sched.schedule(this::checkOnce, 3, TimeUnit.SECONDS);
        sched.scheduleAtFixedRate(this::checkOnce, 6, 6, TimeUnit.HOURS);
    }

    public VersionInfo snapshot() {
        VersionInfo v = latest.get();
        if (v == null) {
            // Pre-first-poll — return what we know about the running app so
            // the UI can render at least the current version.
            return new VersionInfo(currentVersion, currentVersion, false, null);
        }
        return v;
    }

    private void checkOnce() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(RELEASES_URL))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "intelli-mailbox/" + currentVersion)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.debug("UpdateChecker: GitHub returned HTTP {}", resp.statusCode());
                return;
            }
            JsonNode root = json.readTree(resp.body());
            String tag = root.path("tag_name").asText("");
            String htmlUrl = root.path("html_url").asText("");
            String published = tag.startsWith("v") ? tag.substring(1) : tag;
            if (published.isBlank()) return;
            boolean newer = isNewer(published, currentVersion);
            VersionInfo info = new VersionInfo(currentVersion, published, newer,
                    htmlUrl.isBlank() ? null : htmlUrl);
            latest.set(info);
            if (newer) {
                logger.info("UpdateChecker: newer release available — {} > {} ({})",
                        published, currentVersion, htmlUrl);
            }
        } catch (Throwable t) {
            logger.debug("UpdateChecker: poll failed ({})", t.toString());
        }
    }

    /** Compare semantic-ish versions ("0.1.3" vs "0.1.4"). Pure string segments,
     *  tolerant of pre-release suffixes ("0.1.4-rc1" → still treated as 0.1.4). */
    static boolean isNewer(String latest, String current) {
        int[] a = parts(latest);
        int[] b = parts(current);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int ai = i < a.length ? a[i] : 0;
            int bi = i < b.length ? b[i] : 0;
            if (ai != bi) return ai > bi;
        }
        return false;
    }

    private static int[] parts(String v) {
        if (v == null) return new int[0];
        String trimmed = v.split("[^0-9.]", 2)[0];
        if (trimmed.isEmpty()) return new int[0];
        String[] segs = trimmed.split("\\.");
        int[] out = new int[segs.length];
        for (int i = 0; i < segs.length; i++) {
            try { out[i] = Integer.parseInt(segs[i]); }
            catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }

    public record VersionInfo(String current, String latest,
                               boolean updateAvailable, String releaseUrl) {}
}
