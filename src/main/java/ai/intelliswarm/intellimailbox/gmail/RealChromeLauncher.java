package ai.intelliswarm.intellimailbox.gmail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Launches the user's REAL Chrome installation in a separate process with the CDP
 * debug port open, then waits for that port to become reachable.
 *
 * <p>Why we need this: Google's signin flow flags Playwright's bundled Chromium
 * as "not a secure browser" and refuses to authenticate. Connecting to a real
 * Chrome via CDP sidesteps that — Chrome IS a Chrome, no fingerprint mismatch.
 *
 * <p>Spawned Chrome runs with a dedicated {@code --user-data-dir} so it doesn't
 * collide with the user's everyday browsing profile. Cookies persist between
 * runs, so signin happens at most once.
 */
public final class RealChromeLauncher {

    private static final Logger logger = LoggerFactory.getLogger(RealChromeLauncher.class);

    /**
     * @param cdpUrl    e.g. {@code http://localhost:9222}
     * @param wasAlreadyRunning  true if Chrome was already listening on the port
     * @param process   the launched Process, or null if Chrome was already running
     */
    public record LaunchResult(String cdpUrl, boolean wasAlreadyRunning, Process process) {}

    public static LaunchResult launch(Path userDataDir, int port) {
        String cdpUrl = "http://localhost:" + port;

        if (cdpReachable(cdpUrl)) {
            logger.info("Found Chrome already listening on {}", cdpUrl);
            return new LaunchResult(cdpUrl, true, null);
        }

        String chromePath = findChromeExecutable()
                .orElseThrow(() -> new IllegalStateException(
                        "Couldn't find Chrome / Chromium / Edge / Brave on this machine.\n"
                                + "Set swarmai.tools.browser.chrome-path=/full/path/to/chrome.exe to override."));

        try { Files.createDirectories(userDataDir); } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot create user-data-dir " + userDataDir + ": " + e.getMessage(), e);
        }

        ProcessBuilder pb = new ProcessBuilder(
                chromePath,
                "--remote-debugging-port=" + port,
                "--user-data-dir=" + userDataDir.toAbsolutePath(),
                "--no-first-run",
                "--no-default-browser-check"
        );
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to launch Chrome: " + chromePath + " — " + e.getMessage(), e);
        }

        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (cdpReachable(cdpUrl)) {
                logger.info("Chrome ready on {} (PID={})", cdpUrl, process.pid());
                return new LaunchResult(cdpUrl, false, process);
            }
            try { Thread.sleep(250); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        process.destroy();
        throw new IllegalStateException(
                "Chrome started but never opened the CDP port at " + cdpUrl
                        + ". Try deleting " + userDataDir + " and rerunning.");
    }

    public static java.util.Optional<String> findChromeExecutable() {
        String override = System.getProperty("swarmai.tools.browser.chrome-path");
        if (override != null && !override.isBlank() && new File(override).canExecute()) {
            return java.util.Optional.of(override);
        }

        String osName = System.getProperty("os.name", "").toLowerCase();
        List<String> candidates;
        if (osName.contains("win")) {
            String programFiles  = System.getenv("ProgramFiles");
            String programFilesX = System.getenv("ProgramFiles(x86)");
            String localAppData  = System.getenv("LOCALAPPDATA");
            candidates = new java.util.ArrayList<>();
            for (String pf : List.of(orDefault(programFiles, "C:\\Program Files"),
                                      orDefault(programFilesX, "C:\\Program Files (x86)"))) {
                candidates.add(pf + "\\Google\\Chrome\\Application\\chrome.exe");
                candidates.add(pf + "\\Google\\Chrome Beta\\Application\\chrome.exe");
                candidates.add(pf + "\\Google\\Chrome Dev\\Application\\chrome.exe");
            }
            if (localAppData != null) {
                candidates.add(localAppData + "\\Google\\Chrome\\Application\\chrome.exe");
                candidates.add(localAppData + "\\Google\\Chrome SxS\\Application\\chrome.exe");
                candidates.add(localAppData + "\\Chromium\\Application\\chrome.exe");
                candidates.add(localAppData + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe");
            }
            for (String pf : List.of(orDefault(programFiles, "C:\\Program Files"),
                                      orDefault(programFilesX, "C:\\Program Files (x86)"))) {
                candidates.add(pf + "\\Microsoft\\Edge\\Application\\msedge.exe");
            }
        } else if (osName.contains("mac")) {
            candidates = List.of(
                    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                    System.getProperty("user.home")
                            + "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                    "/Applications/Google Chrome Beta.app/Contents/MacOS/Google Chrome Beta",
                    "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
                    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                    "/Applications/Chromium.app/Contents/MacOS/Chromium");
        } else {
            candidates = List.of(
                    "/usr/bin/google-chrome",
                    "/usr/bin/google-chrome-stable",
                    "/usr/bin/chromium",
                    "/usr/bin/chromium-browser",
                    "/snap/bin/chromium",
                    "/opt/google/chrome/google-chrome",
                    "/usr/bin/microsoft-edge",
                    "/usr/bin/microsoft-edge-stable",
                    "/usr/bin/brave-browser");
        }
        for (String c : candidates) {
            if (new File(c).canExecute()) return java.util.Optional.of(c);
        }
        for (String name : List.of("chrome.exe", "chrome", "chromium", "msedge.exe", "msedge")) {
            String found = lookupOnPath(name, osName.contains("win"));
            if (found != null) return java.util.Optional.of(found);
        }
        return java.util.Optional.empty();
    }

    private static String orDefault(String s, String def) { return s == null || s.isBlank() ? def : s; }

    private static String lookupOnPath(String name, boolean windows) {
        try {
            ProcessBuilder pb = new ProcessBuilder(windows ? "where" : "which", name);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor();
            if (p.exitValue() != 0) return null;
            String first = new String(out).split("\\R", 2)[0].trim();
            return (!first.isEmpty() && new File(first).canExecute()) ? first : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean cdpReachable(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/json/version"))
                    .timeout(Duration.ofSeconds(2))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && resp.body().contains("webSocketDebuggerUrl");
        } catch (Exception e) {
            return false;
        }
    }

    private RealChromeLauncher() { }
}
