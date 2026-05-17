package ai.intelliswarm.intellimailbox.diagnostics;

import ai.intelliswarm.intellimailbox.settings.LlmProvider;
import ai.intelliswarm.intellimailbox.settings.LlmSettings;
import ai.intelliswarm.intellimailbox.settings.LlmTransport;
import ai.intelliswarm.intellimailbox.settings.ProviderCatalog;
import ai.intelliswarm.intellimailbox.settings.ProviderConfig;
import ai.intelliswarm.intellimailbox.settings.SettingsStore;
import ai.intelliswarm.swarmai.tool.common.BrowserTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Welcome-screen diagnostics:
 * <pre>
 *   GET  /api/diagnostics                       run all checks, return checklist
 *   POST /api/diagnostics/fix/{action}          apply auto-fix (returns 200 OK)
 *   GET  /api/diagnostics/fix/pull-model        SSE stream of model-pull progress
 *   GET  /api/diagnostics/fix/open-ollama-download  redirect to ollama.com/download
 * </pre>
 *
 * <p>Auto-fixes are intentionally narrow:
 * <ul>
 *   <li>{@code pull-model} — calls Ollama's own {@code POST /api/pull}
 *       with the configured model name. Streams progress as SSE so the UI
 *       can show a live progress bar to non-technical users.</li>
 *   <li>{@code open-gmail} — re-navigates the attached Chrome to Gmail
 *       (in case the user closed the tab).</li>
 *   <li>{@code open-ollama-download} — opens the Ollama download page in
 *       the user's default browser. We can't auto-install Ollama (needs
 *       admin) but we can lower the friction.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    private static final Logger logger = LoggerFactory.getLogger(DiagnosticsController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private final DiagnosticsService service;
    private final ObjectProvider<BrowserTool> browserToolProvider;

    public DiagnosticsController(DiagnosticsService service,
                                 ObjectProvider<BrowserTool> browserToolProvider) {
        this.service = service;
        this.browserToolProvider = browserToolProvider;
    }

    /** Check ids that are advisory only — their warn/fail status doesn't
     *  block the welcome-screen auto-start countdown. Currently just the
     *  embedding model: chat semantic search degrades gracefully without
     *  it (10 other tools still work), so blocking onboarding on this
     *  optional feature would be worse UX than letting the user proceed
     *  and pull the model later from Settings. */
    private static final java.util.Set<String> ADVISORY_CHECK_IDS =
            java.util.Set.of("llm.embedding");

    @GetMapping
    public Map<String, Object> diagnose() {
        List<DiagnosticCheck> checks = service.runAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", checks);
        out.put("ready", checks.stream().allMatch(c ->
                "ok".equals(c.status()) || ADVISORY_CHECK_IDS.contains(c.id())));
        // Tally so the UI can show "3 of 5 ready".
        long ok   = checks.stream().filter(c -> "ok".equals(c.status())).count();
        long fail = checks.stream().filter(c -> "fail".equals(c.status())).count();
        long warn = checks.stream().filter(c -> "warn".equals(c.status())).count();
        out.put("counts", Map.of("ok", ok, "warn", warn, "fail", fail, "total", checks.size()));
        return out;
    }

    /**
     * One-shot non-streaming auto-fixes. Use this for actions that complete
     * quickly (open Gmail tab, open download page). Long-running actions
     * use the SSE variant below.
     */
    @PostMapping(path = "/fix/{action}")
    public ResponseEntity<Map<String, Object>> fix(@PathVariable("action") String action) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("action", action);
        try {
            switch (action) {
                case "open-gmail" -> openGmailInAttachedChrome(resp);
                case "open-ollama-download" -> openOllamaDownload(resp);
                default -> {
                    resp.put("ok", false);
                    resp.put("reason", "unknown action: " + action);
                }
            }
        } catch (Throwable t) {
            logger.warn("Auto-fix '{}' failed: {}", action, t.toString());
            resp.put("ok", false);
            resp.put("reason", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    /**
     * SSE stream of {@code ollama pull} progress. The user clicks the
     * "Download AI model" button on the welcome screen and watches the bar
     * fill. We piggyback on Ollama's own {@code /api/pull} which streams
     * NDJSON progress lines — we forward each one as an SSE event the UI
     * renders into a percentage.
     */
    @GetMapping(path = "/fix/pull-model", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter pullModel() {
        SseEmitter emitter = new SseEmitter(0L);
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "diagnostics-pull-model");
            t.setDaemon(true);
            return t;
        }).submit(() -> streamModelPull(emitter, null /* use chat model from settings */));
        return emitter;
    }

    /** Pulls the embedding model that powers chat semantic search. Mirrors
     *  pull-model but hard-codes the model so it isn't tied to the active
     *  chat-model setting — embeddings and chat are separate dimensions. */
    @GetMapping(path = "/fix/pull-embedding-model", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter pullEmbeddingModel() {
        SseEmitter emitter = new SseEmitter(0L);
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "diagnostics-pull-embedding-model");
            t.setDaemon(true);
            return t;
        }).submit(() -> streamModelPull(emitter, DiagnosticsService.EMBEDDING_MODEL));
        return emitter;
    }

    /**
     * SSE stream of an Ollama install attempt. Picks the user's OS package
     * manager (winget on Windows, brew on macOS, the official curl|sh script
     * on Linux), spawns it via {@link ProcessBuilder}, forwards stdout+stderr
     * line-by-line as SSE events. If the package manager isn't on the user's
     * PATH, we surface that clearly and the UI falls back to the download
     * link.
     */
    @GetMapping(path = "/fix/install-ollama", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter installOllama() {
        SseEmitter emitter = new SseEmitter(0L);
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "diagnostics-install-ollama");
            t.setDaemon(true);
            return t;
        }).submit(() -> streamOllamaInstall(emitter));
        return emitter;
    }

    /**
     * Spawn {@code ollama serve} in the background and poll the configured
     * baseUrl until /api/tags responds (or we time out). Used when Ollama is
     * installed but the daemon isn't currently running — far less invasive
     * than re-running the installer.
     */
    @GetMapping(path = "/fix/start-ollama", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter startOllama() {
        SseEmitter emitter = new SseEmitter(0L);
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "diagnostics-start-ollama");
            t.setDaemon(true);
            return t;
        }).submit(() -> streamOllamaStart(emitter));
        return emitter;
    }

    private void streamOllamaStart(SseEmitter e) {
        if (!commandAvailable("ollama")) {
            sendSseStatus(e, "error",
                    "Couldn't find the ollama binary on PATH. Install it first via "
                            + "\"Install Ollama for me\".",
                    null);
            try { e.complete(); } catch (Exception ignored) { }
            return;
        }
        sendSseStatus(e, "starting", "Launching Ollama in the background…", null);
        try {
            ProcessBuilder pb = new ProcessBuilder("ollama", "serve");
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start(); // fire-and-forget — the daemon runs until the user logs out / shuts down

            LlmSettings s = SettingsStore.load();
            LlmProvider p = ProviderCatalog.byId(s.activeProvider())
                    .orElseGet(() -> ProviderCatalog.byId(ProviderCatalog.defaultProviderId()).orElseThrow());
            ProviderConfig cfg = s.configFor(p.id());
            String baseUrl = cfg.baseUrl().isBlank() ? p.defaultBaseUrl() : cfg.baseUrl();
            URI tagsUri = URI.create(baseUrl.replaceAll("/+$", "") + "/api/tags");

            long deadline = System.currentTimeMillis() + 12_000;
            int tries = 0;
            while (System.currentTimeMillis() < deadline) {
                tries++;
                try {
                    HttpResponse<Void> r = HTTP.send(
                            HttpRequest.newBuilder().uri(tagsUri)
                                    .timeout(Duration.ofSeconds(2))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.discarding());
                    if (r.statusCode() / 100 == 2) {
                        sendSseStatus(e, "done",
                                "Ollama is now running. Click Re-check to continue.", 100);
                        return;
                    }
                } catch (Exception probeEx) {
                    // Daemon not up yet — keep polling.
                }
                int pct = Math.min(95, (int) ((tries * 100L) / 14)); // soft progress until we land
                sendSseStatus(e, "progress",
                        "Waiting for Ollama to come up… (attempt " + tries + ")", pct);
                Thread.sleep(800);
            }
            sendSseStatus(e, "error",
                    "Ollama didn't respond within 12 seconds. "
                            + "Try opening it manually from your Start menu / Applications, "
                            + "then click Re-check.",
                    null);
        } catch (Exception ex) {
            sendSseStatus(e, "error",
                    "Couldn't start Ollama: " + ex.getMessage(), null);
        } finally {
            try { e.complete(); } catch (Exception ignored) { }
        }
    }

    private void streamOllamaInstall(SseEmitter e) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] cmd;
        String describe;
        String requiredBinary;
        if (os.contains("win")) {
            cmd = new String[] {
                    "winget", "install", "Ollama.Ollama",
                    "--accept-source-agreements", "--accept-package-agreements",
                    "--silent"
            };
            describe = "Installing Ollama via winget…";
            requiredBinary = "winget";
        } else if (os.contains("mac") || os.contains("darwin")) {
            cmd = new String[] { "/bin/bash", "-lc",
                    "brew install ollama && brew services start ollama" };
            describe = "Installing Ollama via Homebrew…";
            requiredBinary = "brew";
        } else {
            cmd = new String[] { "/bin/bash", "-lc",
                    "curl -fsSL https://ollama.com/install.sh | sh" };
            describe = "Installing Ollama via the official Linux script…";
            requiredBinary = "curl";
        }
        sendSseStatus(e, "starting", describe, null);
        sendSseStatus(e, "progress", "$ " + String.join(" ", cmd), null);
        try {
            // Pre-flight: is the package-manager binary even on PATH? Failing
            // here gives the user a far better message than a process-spawn
            // exception buried in the SSE stream.
            if (!commandAvailable(requiredBinary)) {
                sendSseStatus(e, "error",
                        "Couldn't find " + requiredBinary + " on this machine. "
                                + "Click \"Open Ollama download page\" instead and run the installer manually.",
                        null);
                return;
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sendSseStatus(e, "progress", line, null);
                }
            }
            int exit = p.waitFor();
            if (exit == 0) {
                sendSseStatus(e, "done",
                        "Ollama is installed. The AI engine should start automatically — "
                                + "click Re-check below in a few seconds.",
                        100);
            } else {
                sendSseStatus(e, "error",
                        "The installer exited with code " + exit
                                + ". Try the manual download link below.",
                        null);
            }
        } catch (Exception ex) {
            sendSseStatus(e, "error",
                    "Couldn't run the installer: " + ex.getMessage()
                            + ". Use the manual download link below.",
                    null);
        } finally {
            try { e.complete(); } catch (Exception ignored) { }
        }
    }

    /** Cheap "is this binary on PATH" check via {@code where}/{@code which}. */
    private static boolean commandAvailable(String binary) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] probe = os.contains("win")
                ? new String[] { "where", binary }
                : new String[] { "/bin/bash", "-lc", "command -v " + binary };
        try {
            Process p = new ProcessBuilder(probe).redirectErrorStream(true).start();
            // Drain so the process can exit promptly.
            try (var in = p.getInputStream()) { in.readAllBytes(); }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void streamModelPull(SseEmitter emitter, String overrideModel) {
        LlmSettings s = SettingsStore.load();
        LlmProvider p = ProviderCatalog.byId(s.activeProvider())
                .orElseGet(() -> ProviderCatalog.byId(ProviderCatalog.defaultProviderId()).orElseThrow());
        ProviderConfig cfg = s.configFor(p.id());

        if (p.transport() != LlmTransport.OLLAMA) {
            sendSseStatus(emitter, "error", "Active provider isn't Ollama — nothing to pull.", null);
            emitter.complete();
            return;
        }
        String baseUrl = cfg.baseUrl().isBlank() ? p.defaultBaseUrl() : cfg.baseUrl();
        // overrideModel lets a caller pin a specific model (e.g. the embedding
        // model for chat) independently of the active chat-model setting.
        // Null falls back to whatever the user has configured.
        String model = (overrideModel != null && !overrideModel.isBlank())
                ? overrideModel
                : (cfg.model().isBlank() ? p.defaultModel() : cfg.model());

        sendSseStatus(emitter, "starting",
                "Asking Ollama to download " + model + "…", null);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/api/pull"))
                    .timeout(Duration.ofMinutes(30)) // pulls can be slow
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"name\":\"" + escapeJson(model) + "\",\"stream\":true}"))
                    .build();
            HttpClient longHttp = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<java.io.InputStream> resp = longHttp.send(req,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                sendSseStatus(emitter, "error",
                        "Ollama replied with HTTP " + resp.statusCode()
                                + ". Make sure the AI engine is running.", null);
                emitter.complete();
                return;
            }
            // Ollama's /api/pull streams NDJSON lines like:
            //   {"status":"pulling manifest"}
            //   {"status":"downloading","digest":"sha256:…","total":1024,"completed":256}
            //   {"status":"success"}
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(resp.body(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        JsonNode n = MAPPER.readTree(line);
                        String status = n.path("status").asText("");
                        long total = n.path("total").asLong(0);
                        long done  = n.path("completed").asLong(0);
                        Integer pct = (total > 0) ? (int) ((done * 100) / total) : null;
                        sendSseStatus(emitter,
                                "success".equals(status) ? "done" : "progress",
                                status, pct);
                        if ("success".equals(status)) break;
                    } catch (Exception parseEx) {
                        // Non-JSON line — forward verbatim.
                        sendSseStatus(emitter, "progress", line, null);
                    }
                }
            }
            sendSseStatus(emitter, "done", "Model " + model + " is ready.", 100);
        } catch (Exception e) {
            sendSseStatus(emitter, "error",
                    "Couldn't download the model: " + e.getMessage(), null);
        } finally {
            try { emitter.complete(); } catch (Exception ignored) { }
        }
    }

    private static void sendSseStatus(SseEmitter e, String phase, String message, Integer pct) {
        try {
            Map<String, Object> evt = new LinkedHashMap<>();
            evt.put("phase", phase);
            evt.put("message", message);
            if (pct != null) evt.put("percent", pct);
            e.send(SseEmitter.event().name("progress").data(evt));
        } catch (IOException io) {
            // Client disconnected — nothing more to do.
        }
    }

    private void openGmailInAttachedChrome(Map<String, Object> resp) {
        BrowserTool browser = browserToolProvider.getIfAvailable();
        if (browser == null) {
            resp.put("ok", false);
            resp.put("reason", "browser not connected");
            return;
        }
        // Best-effort: re-navigate the attached Chrome to Gmail. The CDP
        // attach is still alive even if the user closed the visible tab.
        try {
            browser.execute(Map.of("operation", "navigate",
                    "url", "https://mail.google.com/mail/u/0/#inbox",
                    "timeout_ms", 5_000));
            resp.put("ok", true);
            resp.put("message", "Opened Gmail in the attached Chrome window — sign in there.");
        } catch (Exception e) {
            resp.put("ok", false);
            resp.put("reason", e.getMessage());
        }
    }

    private void openOllamaDownload(Map<String, Object> resp) {
        // Try opening the system default browser. On WSL / headless this
        // often fails — the UI also surfaces the URL for manual click.
        String url = "https://ollama.com/download";
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new java.net.URI(url));
                resp.put("ok", true);
                resp.put("message", "Opened the Ollama download page in your default browser.");
                resp.put("url", url);
                return;
            }
        } catch (Exception ignored) { }
        resp.put("ok", true);
        resp.put("message", "Open this link to download Ollama:");
        resp.put("url", url);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
