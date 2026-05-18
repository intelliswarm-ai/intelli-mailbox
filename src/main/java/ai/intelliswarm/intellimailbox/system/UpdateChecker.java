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

    /** GitHub "latest release" endpoint — overridable for local end-to-end
     *  testing of the self-update flow. Set either system property
     *  {@code intellimailbox.update.releases-url} or env var
     *  {@code INTELLIMAILBOX_RELEASES_URL} to a URL that returns the same
     *  JSON shape (e.g. a static file served by {@code python -m http.server})
     *  to exercise the Download → Install path without publishing a release. */
    private static final String DEFAULT_RELEASES_URL =
            "https://api.github.com/repos/intelliswarm-ai/intelli-mailbox/releases/latest";
    private static final String RELEASES_URL = resolveReleasesUrl();

    private static String resolveReleasesUrl() {
        String prop = System.getProperty("intellimailbox.update.releases-url");
        if (prop != null && !prop.isBlank()) return prop.trim();
        String env = System.getenv("INTELLIMAILBOX_RELEASES_URL");
        if (env != null && !env.isBlank()) return env.trim();
        return DEFAULT_RELEASES_URL;
    }

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
        if (!DEFAULT_RELEASES_URL.equals(RELEASES_URL)) {
            // Loud log so this can never silently leak into a release — if you
            // see this line in a user's app.log, the env override is set.
            logger.warn("UpdateChecker: releases URL OVERRIDDEN to {} (test mode)", RELEASES_URL);
        }
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
            return new VersionInfo(currentVersion, currentVersion, false, null, null, null, 0L);
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
            Asset asset = pickAssetForCurrentOs(root.path("assets"));
            VersionInfo info = new VersionInfo(currentVersion, published, newer,
                    htmlUrl.isBlank() ? null : htmlUrl,
                    asset == null ? null : asset.url,
                    asset == null ? null : asset.name,
                    asset == null ? 0L : asset.size);
            latest.set(info);
            if (newer) {
                logger.info("UpdateChecker: newer release available — {} > {} ({})",
                        published, currentVersion, htmlUrl);
            }
        } catch (Throwable t) {
            logger.debug("UpdateChecker: poll failed ({})", t.toString());
        }
    }

    /**
     * Choose the GitHub release asset that matches the host OS so the in-app
     * "Download & install" flow can fetch a directly installable file.
     * Returns {@code null} if no matching asset is published — the UI then
     * falls back to a plain "View release notes" link.
     */
    private static Asset pickAssetForCurrentOs(JsonNode assets) {
        if (assets == null || !assets.isArray() || assets.isEmpty()) return null;
        String os = System.getProperty("os.name", "").toLowerCase();
        String wanted;
        if (os.contains("win")) wanted = ".msi";
        else if (os.contains("mac") || os.contains("darwin")) wanted = ".dmg";
        else wanted = ".deb"; // best default for our Linux installer story
        for (JsonNode a : assets) {
            String name = a.path("name").asText("");
            if (name.toLowerCase().endsWith(wanted)) {
                return new Asset(
                        a.path("browser_download_url").asText(""),
                        name,
                        a.path("size").asLong(0L));
            }
        }
        return null;
    }

    private record Asset(String url, String name, long size) {}

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
                               boolean updateAvailable, String releaseUrl,
                               /** OS-matching installer asset URL on GitHub (MSI on Windows,
                                *  DMG on macOS, DEB on Linux). Null when the release has no
                                *  asset for the host OS. */
                               String assetUrl,
                               /** Filename for the asset, used as the on-disk download name. */
                               String assetName,
                               /** Asset size in bytes from GitHub's metadata, for the UI
                                *  progress bar's denominator. 0 when unknown. */
                               long assetSize) {}
}
