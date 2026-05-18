package ai.intelliswarm.intellimailbox.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives the in-app upgrade flow that issue #3 asks for: instead of asking
 * the user to manually download / uninstall / reinstall, the running app
 * fetches the new release asset and launches the OS installer in one click.
 *
 * <p>State machine:
 * <pre>
 *   IDLE ─► DOWNLOADING ─► READY ─► INSTALLING
 *              │              │
 *              └──────────────┴──► FAILED  (from any non-terminal state)
 * </pre>
 *
 * <p>Per-OS install behaviour:
 * <ul>
 *   <li><b>Windows</b> — {@code msiexec /i <msi> /qb /norestart}. UAC prompts
 *       once. {@code /qb} gives basic install UI so the user sees progress
 *       and can cancel; {@code /qn} would be silent but requires already
 *       being elevated, which we are not.</li>
 *   <li><b>macOS</b> — {@code open <dmg>} to mount the disk image. Apple's
 *       sandbox + Gatekeeper prevent a true unattended app-bundle replace;
 *       this at least skips the "find the file in Downloads" step.</li>
 *   <li><b>Linux</b> — {@code xdg-open <deb>} so the user's preferred package
 *       GUI (gdebi / Discover / Software) handles the privileged install.</li>
 * </ul>
 *
 * <p>The running JVM must terminate before the installer can overwrite the
 * jar. After spawning the installer we ask Spring to exit gracefully — the
 * spawned process inherits no file handles back to the JVM, so killing the
 * JVM is safe.
 */
@Service
public class UpdateInstaller {

    private static final Logger logger = LoggerFactory.getLogger(UpdateInstaller.class);

    public enum State { IDLE, DOWNLOADING, READY, INSTALLING, FAILED }

    /** Where downloaded installers land. Kept under the same .intelliswarm
     *  parent as settings so we don't pollute the user's Downloads folder
     *  with 300 MB MSIs they'd have to clean up later. */
    private static final Path UPDATES_DIR = Paths.get(
            System.getProperty("user.home"), ".intelliswarm", "intellimailbox", "updates");

    private final ConfigurableApplicationContext context;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicLong bytesRead = new AtomicLong(0);
    private final AtomicLong totalBytes = new AtomicLong(0);
    private final AtomicReference<Path> downloadedPath = new AtomicReference<>(null);
    private final AtomicReference<String> targetVersion = new AtomicReference<>(null);
    private final AtomicReference<String> errorMessage = new AtomicReference<>(null);

    public UpdateInstaller(ConfigurableApplicationContext context) {
        this.context = context;
    }

    public synchronized boolean startDownload(String assetUrl, String assetName,
                                              long expectedSize, String version) {
        if (assetUrl == null || assetUrl.isBlank() || assetName == null || assetName.isBlank()) {
            errorMessage.set("No installer asset is available for this OS.");
            state.set(State.FAILED);
            return false;
        }
        // Idempotent — if a download is in flight or already complete for the
        // same version, just keep that state instead of restarting.
        State s = state.get();
        if ((s == State.DOWNLOADING || s == State.READY) && version.equals(targetVersion.get())) {
            return true;
        }
        // Recoverable retry from FAILED is fine — fall through and reset.
        bytesRead.set(0);
        totalBytes.set(expectedSize);
        downloadedPath.set(null);
        errorMessage.set(null);
        targetVersion.set(version);
        state.set(State.DOWNLOADING);

        Thread worker = new Thread(() -> downloadTo(assetUrl, assetName), "intellimailbox-update-download");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /** Number of parallel range-request workers when the server supports it.
     *  Four is the sweet spot in practice — beyond that GitHub's CDN starts
     *  rate-limiting per-IP and we see diminishing returns vs. cost in
     *  thread/socket churn. */
    private static final int PARALLEL_CONNECTIONS = 4;

    /** Files smaller than this go through the single-stream path — the
     *  HEAD probe and chunk-stitch overhead aren't worth it for ~MB-sized
     *  payloads, and most range-broken servers fail gracefully here. */
    private static final long PARALLEL_THRESHOLD_BYTES = 16L * 1024 * 1024;

    /** Retries per chunk before declaring the whole download failed.
     *  Linear backoff: 1s, 2s, 4s. Most flake (idle TCP timeouts, brief
     *  CDN node hiccups) clears inside one attempt; a third gives slow
     *  networks one more chance before we surface the failure. */
    private static final int MAX_CHUNK_ATTEMPTS = 3;

    private void downloadTo(String assetUrl, String assetName) {
        Path target = UPDATES_DIR.resolve(assetName);
        Path tmp = UPDATES_DIR.resolve(assetName + ".part");
        try {
            Files.createDirectories(UPDATES_DIR);
            // Wipe any half-finished previous attempt so we never end up
            // launching a truncated MSI.
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(target);

            AssetMetadata meta = probeAssetMetadata(assetUrl);
            if (meta.contentLength > 0) totalBytes.set(meta.contentLength);

            if (meta.rangesSupported && meta.contentLength >= PARALLEL_THRESHOLD_BYTES) {
                logger.info("UpdateInstaller: downloading {} bytes in {} parallel chunks",
                        meta.contentLength, PARALLEL_CONNECTIONS);
                downloadParallel(assetUrl, tmp, meta.contentLength);
            } else {
                logger.info("UpdateInstaller: downloading single-stream (size={}, rangesOk={})",
                        meta.contentLength, meta.rangesSupported);
                downloadSingleStream(assetUrl, tmp);
            }

            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            downloadedPath.set(target);
            state.set(State.READY);
            logger.info("UpdateInstaller: downloaded {} ({} bytes) to {}",
                    assetName, bytesRead.get(), target);
        } catch (Throwable t) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            fail("Download failed: " + t.getMessage());
            logger.warn("UpdateInstaller: download failed", t);
        }
    }

    /**
     * One-byte ranged GET to discover both the asset's total size and whether
     * the server actually honors ranges. We use a 1-byte GET rather than a
     * HEAD because GitHub's release-asset redirect chain sometimes responds
     * 200 to HEAD without revealing the CDN behind it, and ranges only work
     * on the redirect target. A short ranged GET hits the CDN directly,
     * returns 206 + Content-Range when supported, and lets us read
     * Content-Length from the response without pulling the full body.
     */
    private AssetMetadata probeAssetMetadata(String assetUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(assetUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "intelli-mailbox-updater")
                    .header("Range", "bytes=0-0")
                    .GET().build();
            // Crucially we use ofInputStream and immediately close the body
                            // WITHOUT reading it. The probe only needs the response status
            // and headers — if the server honors Range we get 206 + a 1-byte
            // body (fine to discard), if it ignores Range it would otherwise
            // stream the full 340 MB MSI into heap with ofByteArray, blowing
            // past the packaged -Xmx1g long before we reached the "fall back
            // to single-stream" decision. Closing the stream signals HttpClient
            // to abort the response so no body bytes are buffered.
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = resp.body()) {
                // intentionally not reading — close immediately
                if (body == null) { /* no-op */ }
            }
            if (resp.statusCode() == 206) {
                // 206 Partial Content: parse the total from "Content-Range: bytes 0-0/1234567".
                long total = resp.headers().firstValue("Content-Range")
                        .map(h -> {
                            int slash = h.lastIndexOf('/');
                            if (slash < 0 || slash == h.length() - 1) return 0L;
                            try { return Long.parseLong(h.substring(slash + 1).trim()); }
                            catch (NumberFormatException e) { return 0L; }
                        })
                        .orElse(0L);
                return new AssetMetadata(total, true);
            }
            if (resp.statusCode() / 100 == 2) {
                // Server ignored the Range header and returned the full body
                // (which we just closed without reading). Fall back to
                // single-stream and trust Content-Length if it's there.
                long len = resp.headers().firstValue("Content-Length")
                        .map(Long::parseLong).orElse(0L);
                return new AssetMetadata(len, false);
            }
        } catch (Exception probeFailed) {
            // Probe is best-effort — fall back to single-stream and let the
            // real download report the real HTTP error if there is one.
        }
        return new AssetMetadata(0L, false);
    }

    private record AssetMetadata(long contentLength, boolean rangesSupported) {}

    /**
     * Single-TCP-stream download for small files, or when the server doesn't
     * honor range requests. Kept as the fallback path so weird CDNs (or
     * future test fixtures) that 200-with-full-body still work.
     */
    private void downloadSingleStream(String assetUrl, Path tmp) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(assetUrl))
                .timeout(Duration.ofMinutes(30))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "intelli-mailbox-updater")
                .GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Server returned HTTP " + resp.statusCode());
        }
        resp.headers().firstValue("Content-Length")
                .map(Long::parseLong).ifPresent(totalBytes::set);

        try (InputStream in = resp.body();
             var out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                bytesRead.addAndGet(n);
            }
        }
    }

    /**
     * Parallel range-request download. The file is sliced into
     * {@link #PARALLEL_CONNECTIONS} contiguous byte ranges; each is fetched
     * concurrently and written to its own offset in the same output file via
     * {@link FileChannel#write(ByteBuffer, long)} (which is thread-safe for
     * non-overlapping ranges on every JVM we ship to). The shared
     * {@code bytesRead} atomic gives the UI a continuous progress signal
     * across all workers.
     *
     * <p>On chunk failure, that one chunk is retried up to
     * {@link #MAX_CHUNK_ATTEMPTS} times with linear backoff. If a chunk
     * exhausts its retries, every worker is cancelled and the whole
     * download surfaces as FAILED — partial-resume across attempts isn't
     * worth the bookkeeping cost yet.
     */
    private void downloadParallel(String assetUrl, Path tmp, long totalSize)
            throws IOException, InterruptedException, ExecutionException {
        // Pre-size the file so positioned writes can land at any offset
        // without growing the file under us. RandomAccess open + close
        // sets the length atomically.
        try (FileChannel pre = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            pre.position(totalSize - 1).write(ByteBuffer.wrap(new byte[]{0}));
        }

        long chunkSize = (totalSize + PARALLEL_CONNECTIONS - 1) / PARALLEL_CONNECTIONS;
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_CONNECTIONS, r -> {
            Thread t = new Thread(r, "intellimailbox-update-chunk");
            t.setDaemon(true);
            return t;
        });
        List<Future<?>> tasks = new ArrayList<>();
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            for (int i = 0; i < PARALLEL_CONNECTIONS; i++) {
                long start = i * chunkSize;
                long end = Math.min(start + chunkSize - 1, totalSize - 1);
                if (start > end) break;
                tasks.add(pool.submit(() -> fetchChunk(assetUrl, channel, start, end)));
            }
            for (Future<?> t : tasks) {
                try {
                    t.get();
                } catch (ExecutionException ee) {
                    // One chunk gave up — cancel the rest so we don't keep
                    // burning bandwidth on a download we're about to abort.
                    for (Future<?> other : tasks) other.cancel(true);
                    throw ee;
                }
            }
        } finally {
            pool.shutdownNow();
            // Give the worker threads a beat to clean up their HTTP responses
            // before we let the channel close — otherwise an interrupted
            // worker can still be mid-write when the channel goes away,
            // throwing a noisy AsynchronousCloseException on top of the
            // real cause.
            //noinspection ResultOfMethodCallIgnored
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private Void fetchChunk(String assetUrl, FileChannel channel, long start, long end)
            throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_CHUNK_ATTEMPTS; attempt++) {
            long chunkBytesAccountedFor = 0; // for rollback if this attempt fails mid-stream
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(assetUrl))
                        .timeout(Duration.ofMinutes(15))
                        .header("Accept", "application/octet-stream")
                        .header("User-Agent", "intelli-mailbox-updater")
                        .header("Range", "bytes=" + start + "-" + end)
                        .GET().build();
                HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                // Must be 206. A 200 here means the server (or an intermediate
                // proxy/CDN) silently ignored our Range header and is about to
                // stream the FULL installer to a single chunk worker — which
                // would write the entire file starting at this chunk's offset,
                // clobbering every other chunk's range and producing a
                // corrupted .part file that would still get moved to READY.
                // Refuse and let the chunk retry; if all attempts fail, the
                // download surfaces as FAILED instead of installing a broken
                // MSI. (We close the body immediately to abort the unwanted
                // full-body transfer.)
                if (resp.statusCode() != 206) {
                    try { resp.body().close(); } catch (IOException ignored) { }
                    throw new IOException("Expected HTTP 206 on chunk " + start + "-" + end
                            + " but got " + resp.statusCode() + " (server stopped honoring Range)");
                }
                long pos = start;
                try (InputStream in = resp.body()) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
                        while (bb.hasRemaining()) pos += channel.write(bb, pos);
                        bytesRead.addAndGet(n);
                        chunkBytesAccountedFor += n;
                    }
                }
                return null;
            } catch (IOException ioe) {
                last = ioe;
                // Undo the bytesRead increments from this failed attempt so
                // the progress bar doesn't overshoot when the retry runs.
                if (chunkBytesAccountedFor > 0) bytesRead.addAndGet(-chunkBytesAccountedFor);
                if (attempt < MAX_CHUNK_ATTEMPTS) {
                    Thread.sleep(1000L * (1L << (attempt - 1))); // 1s, 2s, 4s
                }
            }
        }
        throw last != null ? last : new IOException("Chunk " + start + "-" + end + " failed");
    }

    public synchronized boolean startInstall() {
        if (state.get() != State.READY) return false;
        Path file = downloadedPath.get();
        if (file == null || !Files.exists(file)) {
            fail("Downloaded installer is missing on disk.");
            return false;
        }
        state.set(State.INSTALLING);

        // Spawn the installer detached, then ask Spring to exit so jpackage's
        // MSI can replace the jar without "file in use" errors. The installer
        // process keeps running independently because we don't .waitFor() and
        // redirect its streams away from our JVM.
        try {
            List<String> cmd = installerCommand(file);
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            logger.info("UpdateInstaller: launched installer {} — exiting JVM in 1s.", cmd);
        } catch (IOException e) {
            fail("Couldn't launch installer: " + e.getMessage());
            return false;
        }

        // Same 1s deferral pattern as /api/shutdown so the HTTP response
        // returns to the UI before the JVM dies and the connection drops.
        Thread quit = new Thread(() -> {
            try { Thread.sleep(1_000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            try {
                int code = SpringApplication.exit(context, () -> 0);
                System.exit(code);
            } catch (Throwable t) {
                logger.warn("Self-exit after install launch failed: {}", t.toString());
                System.exit(0);
            }
        }, "intellimailbox-update-shutdown");
        quit.setDaemon(false);
        quit.start();
        return true;
    }

    private static List<String> installerCommand(Path file) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String p = file.toAbsolutePath().toString();
        if (os.contains("win")) {
            // /qb = "basic UI" — user sees the progress dialog (and the UAC
            // prompt that necessarily precedes it) but no wizard pages.
            // /norestart = don't reboot Windows even if the package suggests it.
            //
            // REINSTALL=ALL REINSTALLMODE=vomus is the magic incantation for
            // "if a previous install of the same ProductCode is already
            // present, overwrite all files and rewrite registry entries
            // instead of refusing with MSI error 1638". This handles the
            // same-version reinstall case (a user who clicks Install on a
            // banner pointing at the version they already have); on a
            // genuine major-upgrade install (new UpgradeCode/ProductCode)
            // the flags are a no-op because Windows routes through the
            // major-upgrade path before consulting REINSTALL=.
            return List.of("msiexec.exe", "/i", p, "/qb", "/norestart",
                    "REINSTALL=ALL", "REINSTALLMODE=vomus");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return List.of("/usr/bin/open", p);
        }
        return List.of("xdg-open", p);
    }

    private void fail(String message) {
        errorMessage.set(message);
        state.set(State.FAILED);
    }

    public Snapshot snapshot() {
        return new Snapshot(state.get(), bytesRead.get(), totalBytes.get(),
                targetVersion.get(), errorMessage.get());
    }

    public record Snapshot(State state, long bytesRead, long totalBytes,
                            String targetVersion, String error) {}
}
