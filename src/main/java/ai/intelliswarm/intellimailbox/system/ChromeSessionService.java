package ai.intelliswarm.intellimailbox.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * Holds the attached Chrome process + its profile directory so a user can
 * <em>logout completely</em> from the app: close Chrome and delete every
 * persisted credential / cookie / cache file under {@code user-data-dir}.
 *
 * <p>This is the safety hatch the user asked for — distinct from "Quit"
 * (which only stops the JVM). Logout assumes you're about to hand the
 * machine to someone else, or you want zero residual signin state on disk
 * in case another process later tries to read the profile.
 *
 * <p>Wiped state on logout:
 * <ul>
 *   <li>Login Data — saved passwords</li>
 *   <li>Cookies — Gmail's session cookie (the most exploitable item)</li>
 *   <li>Web Data — autofill, addresses, payment methods</li>
 *   <li>History, Top Sites, Visited Links</li>
 *   <li>Network/, Sessions/ — TLS session tickets, in-flight tab state</li>
 *   <li>everything else under the profile dir</li>
 * </ul>
 */
@Service
public class ChromeSessionService {

    private static final Logger logger = LoggerFactory.getLogger(ChromeSessionService.class);

    private volatile Process chromeProcess;
    private volatile Path profileDir;
    private volatile String cdpUrl;

    /** Called once from {@link ai.intelliswarm.intellimailbox.IntelliMailboxApp}
     *  right after RealChromeLauncher.launch returns. */
    public void register(Process process, Path profileDir, String cdpUrl) {
        this.chromeProcess = process;
        this.profileDir = profileDir;
        this.cdpUrl = cdpUrl;
        logger.info("ChromeSessionService: registered profile={} (process={})",
                profileDir, process == null ? "external" : ("PID " + process.pid()));
    }

    public Path getProfileDir() {
        return profileDir;
    }

    /**
     * Close Chrome, wait briefly for file handles to release, then delete the
     * entire profile directory. Returns a {@link LogoutResult} summarising
     * what was done so the UI can show a precise message rather than a
     * generic "logged out".
     */
    public synchronized LogoutResult logoutAndWipe() {
        if (profileDir == null) {
            return new LogoutResult(false, "No Chrome session registered.", 0, 0);
        }

        // 1. Close Chrome — try graceful first, then HTTP Browser.close via
        //    CDP, then forcible. Each step is best-effort; we proceed even
        //    if Chrome was already gone (user could have closed the window).
        boolean closed = closeChrome();
        if (closed) {
            // Give Chrome a moment to flush + release Windows file locks
            // (it holds an exclusive handle on Cookies / Login Data while
            // running and the rmdir below would fail with AccessDenied).
            try { Thread.sleep(800); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        // 2. Delete the profile dir recursively. Each delete is per-file so
        //    a partial failure (one locked file) still wipes everything else.
        long[] counts = deleteDirRecursively(profileDir);
        long deleted = counts[0];
        long failed = counts[1];

        // 3. Forget what we knew about the now-dead session.
        chromeProcess = null;

        String message;
        boolean ok;
        if (failed == 0) {
            message = "Chrome closed and " + deleted + " profile file(s) wiped from "
                    + profileDir + ".";
            ok = true;
        } else {
            // Credential-bearing files (Login Data, Cookies, Web Data) are the
            // ones most often locked by a still-alive Chrome process on
            // Windows. Returning ok=true here would let the frontend shut
            // the app down despite leaving these files on disk — exactly
            // the safety failure logout is meant to prevent. Surface as
            // failure; the UI now shows the message and offers a retry
            // rather than silently completing.
            message = "Couldn't fully wipe the profile: " + failed + " file(s) were locked"
                    + " (Chrome may still be running). Wiped " + deleted + " other file(s)."
                    + " Quit any remaining Chrome windows for this profile and click Logout"
                    + " again. Profile: " + profileDir;
            ok = false;
        }
        logger.info("ChromeSessionService: logout — {}", message);
        return new LogoutResult(ok, message, deleted, failed);
    }

    private boolean closeChrome() {
        Process p = chromeProcess;
        boolean acted = false;

        // Try the CDP Browser.close HTTP shortcut first — only useful when the
        // browser was launched externally (no Process handle). For an
        // owned process the graceful path below is more reliable.
        if (p == null && cdpUrl != null) {
            acted = tryCdpClose(cdpUrl);
        }

        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                boolean exited = p.waitFor(4, TimeUnit.SECONDS);
                if (!exited) {
                    logger.info("ChromeSessionService: Chrome didn't exit in 4s — forcing.");
                    p.destroyForcibly();
                    p.waitFor(2, TimeUnit.SECONDS);
                }
                acted = true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        return acted;
    }

    /** Send {@code POST /json/close} for every Chrome tab as a fallback path
     *  when we don't hold the Process handle (i.e. the user had Chrome
     *  already running when the app started, so RealChromeLauncher attached
     *  rather than spawned). Doesn't kill Chrome itself — the user closes
     *  the window — but at least Gmail's tab is gone. */
    private boolean tryCdpClose(String cdp) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest list = HttpRequest.newBuilder()
                    .uri(URI.create(cdp + "/json/list"))
                    .timeout(Duration.ofSeconds(2))
                    .GET().build();
            HttpResponse<String> resp = client.send(list, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return false;
            // Bare regex over the JSON — no need to pull a parser in for this.
            // CDP target ids are opaque tokens — across Chrome versions they've
            // been observed as upper-hex (legacy), lower-hex, and UUID-shape
            // with hyphens. Match all three: [A-Fa-f0-9-]+ with a 6-char floor
            // to reject obvious noise / "0" / "1" style id fields from other
            // objects nested in the same JSON document.
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"id\"\\s*:\\s*\"([A-Fa-f0-9-]{6,})\"")
                    .matcher(resp.body());
            int closed = 0;
            while (m.find()) {
                String id = m.group(1);
                try {
                    HttpRequest close = HttpRequest.newBuilder()
                            .uri(URI.create(cdp + "/json/close/" + id))
                            .timeout(Duration.ofSeconds(2))
                            .GET().build();
                    client.send(close, HttpResponse.BodyHandlers.discarding());
                    closed++;
                } catch (Exception ignored) { /* per-tab best-effort */ }
            }
            return closed > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns {@code {deleted, failed}}. Walks deepest-first so directories
     *  empty before we try to remove them. */
    private static long[] deleteDirRecursively(Path root) {
        long deleted = 0, failed = 0;
        if (!Files.isDirectory(root)) return new long[]{0, 0};
        try (var stream = Files.walk(root).sorted(Comparator.reverseOrder())) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.equals(root)) continue;
                try {
                    Files.delete(p);
                    deleted++;
                } catch (IOException e) {
                    failed++;
                    logger.debug("ChromeSessionService: couldn't delete {} ({})",
                            p, e.toString());
                }
            }
            // Try to remove the root last — if it's the configured user-data-dir
            // we want the next run to recreate it fresh.
            try {
                Files.delete(root);
                deleted++;
            } catch (IOException e) {
                failed++;
                logger.debug("ChromeSessionService: couldn't remove dir {} ({})",
                        root, e.toString());
            }
        } catch (IOException e) {
            logger.warn("ChromeSessionService: walk failed for {} ({})", root, e.toString());
        }
        return new long[]{deleted, failed};
    }

    public record LogoutResult(boolean ok, String message,
                                long filesDeleted, long filesFailed) {}
}
