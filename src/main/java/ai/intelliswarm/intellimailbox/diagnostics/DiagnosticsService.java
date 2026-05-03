package ai.intelliswarm.intellimailbox.diagnostics;

import ai.intelliswarm.intellimailbox.settings.LlmProvider;
import ai.intelliswarm.intellimailbox.settings.LlmSettings;
import ai.intelliswarm.intellimailbox.settings.LlmTransport;
import ai.intelliswarm.intellimailbox.settings.ProviderCatalog;
import ai.intelliswarm.intellimailbox.settings.ProviderConfig;
import ai.intelliswarm.intellimailbox.settings.SettingsStore;
import ai.intelliswarm.intellimailbox.settings.SystemInfo;
import ai.intelliswarm.swarmai.tool.common.BrowserTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs preflight checks the welcome screen surfaces to non-technical users:
 * is the AI engine running, is the model downloaded, is the browser
 * connected, is Gmail signed in. Every check returns a {@link DiagnosticCheck}
 * with plain-English wording — no jargon — plus an optional auto-fix action
 * the UI exposes as a single button.
 *
 * <p>The checks are deterministic (HTTP probes + a tiny BrowserTool DOM
 * scrape) for speed and reliability — running an LLM agent for "is Ollama
 * up?" would be both slow and ironic when the LLM itself is what we're
 * testing. The agentic tools come in for the auto-fix flow:
 * {@code ollama pull <model>} streams via Ollama's own HTTP API, no shell
 * required, works the same on Windows / macOS / Linux.
 */
@Service
public class DiagnosticsService {

    private static final Logger logger = LoggerFactory.getLogger(DiagnosticsService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private final ObjectProvider<BrowserTool> browserToolProvider;

    public DiagnosticsService(ObjectProvider<BrowserTool> browserToolProvider) {
        this.browserToolProvider = browserToolProvider;
    }

    public List<DiagnosticCheck> runAll() {
        LlmSettings s = SettingsStore.load();
        LlmProvider provider = ProviderCatalog.byId(s.activeProvider())
                .orElseGet(() -> ProviderCatalog.byId(ProviderCatalog.defaultProviderId()).orElseThrow());
        ProviderConfig cfg = s.configFor(provider.id());

        List<DiagnosticCheck> out = new ArrayList<>();
        out.add(checkProvider(provider, cfg));
        out.add(checkLlmReachable(provider, cfg));
        out.add(checkLlmModelReady(provider, cfg));
        out.add(checkChromeAttached());
        out.add(checkGmailSignedIn());
        return out;
    }

    /** Always informational — confirms which provider is active. */
    private DiagnosticCheck checkProvider(LlmProvider p, ProviderConfig cfg) {
        String label = p.isLocal()
                ? "AI runs on your computer (private)"
                : "AI runs in the cloud at " + p.label();
        return DiagnosticCheck.ok("provider", label,
                "Configured to use " + p.label() + " · model "
                        + (cfg.model().isBlank() ? p.defaultModel() : cfg.model()));
    }

    /** Can we reach the LLM endpoint? Different shapes per transport. */
    private DiagnosticCheck checkLlmReachable(LlmProvider p, ProviderConfig cfg) {
        String baseUrl = cfg.baseUrl().isBlank() ? p.defaultBaseUrl() : cfg.baseUrl();
        if (p.transport() == LlmTransport.OLLAMA) {
            try {
                HttpResponse<String> r = HTTP.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/api/tags"))
                                .timeout(Duration.ofSeconds(2))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() / 100 == 2) {
                    return DiagnosticCheck.ok("llm.reachable",
                            "AI engine is running",
                            "Ollama is responding at " + baseUrl);
                }
                return DiagnosticCheck.fail("llm.reachable",
                        "AI engine isn't responding",
                        "Ollama answered with HTTP " + r.statusCode() + " at " + baseUrl,
                        "Open Ollama from your Start menu (or Applications on Mac) so the engine "
                                + "is running, then click Re-check below.");
            } catch (Exception e) {
                // Two common cases: (1) Ollama not installed, (2) installed but not running.
                // We can't reliably distinguish without a process probe — give the most
                // common installation guidance.
                String os = String.valueOf(SystemInfo.snapshot().get("osFamily"));
                String installCmd = switch (os) {
                    case "windows" -> "winget install Ollama.Ollama";
                    case "mac"     -> "brew install ollama";
                    default         -> "curl -fsSL https://ollama.com/install.sh | sh";
                };
                return DiagnosticCheck.fail("llm.reachable",
                        "AI engine not found",
                        "Couldn't reach the local AI engine. It's either not installed yet, "
                                + "or installed but not currently running.",
                        "Click \"Install Ollama for me\" and we'll install it automatically using "
                                + "your system's package manager. The download is about 250 MB, "
                                + "one-time. Ollama then runs in the background whenever your computer "
                                + "is on. (If your machine doesn't have a package manager, "
                                + "click \"Open Ollama download page\" to install manually.)")
                        .withFixCommand(installCmd)
                        .withAutoFix("install-ollama")
                        .withHelpUrl("https://ollama.com/download");
            }
        }
        // Cloud providers: check the API key + a low-cost reachability probe.
        if (p.requiresApiKey() && !cfg.hasApiKey()) {
            return DiagnosticCheck.fail("llm.reachable",
                    "AI service not configured",
                    p.label() + " needs an API key before it can answer.",
                    "Open Settings (gear icon, top-right) and paste your "
                            + p.label() + " API key. " + (p.signupUrl() == null ? ""
                            : "You can get one at " + p.signupUrl() + "."));
        }
        try {
            HttpResponse<Void> r = HTTP.send(
                    HttpRequest.newBuilder().uri(URI.create(baseUrl))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            // 401/403 from a cloud provider just means "you didn't auth" — the host is up.
            return DiagnosticCheck.ok("llm.reachable",
                    "AI service is reachable",
                    p.label() + " responded (HTTP " + r.statusCode() + ")");
        } catch (Exception e) {
            return DiagnosticCheck.fail("llm.reachable",
                    "Can't reach the AI service",
                    "We couldn't connect to " + baseUrl + ".",
                    "Check that you're online. If you're behind a corporate proxy, "
                            + p.label() + " may be blocked from this network.");
        }
    }

    /** For Ollama: is the configured model present in /api/tags? Cloud: skip. */
    private DiagnosticCheck checkLlmModelReady(LlmProvider p, ProviderConfig cfg) {
        if (p.transport() != LlmTransport.OLLAMA) {
            return DiagnosticCheck.ok("llm.model",
                    "AI model is configured",
                    "Cloud providers manage their own models. You'll use "
                            + (cfg.model().isBlank() ? p.defaultModel() : cfg.model()) + ".");
        }
        String baseUrl = cfg.baseUrl().isBlank() ? p.defaultBaseUrl() : cfg.baseUrl();
        String model = cfg.model().isBlank() ? p.defaultModel() : cfg.model();
        try {
            HttpResponse<String> r = HTTP.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/api/tags"))
                            .timeout(Duration.ofSeconds(2))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                return DiagnosticCheck.warn("llm.model",
                        "Can't tell if the AI model is ready",
                        "Ollama isn't responding, so we can't list installed models.",
                        "Get the AI engine running first (the check above), then we'll re-check this one.");
            }
            JsonNode root = MAPPER.readTree(r.body());
            JsonNode arr = root.path("models");
            boolean hasModel = false;
            List<String> installed = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode m : arr) {
                    String name = m.path("name").asText("");
                    installed.add(name);
                    if (name.equalsIgnoreCase(model)) hasModel = true;
                }
            }
            if (hasModel) {
                return DiagnosticCheck.ok("llm.model",
                        "AI model is downloaded",
                        model + " is ready to use.");
            }
            return DiagnosticCheck.fail("llm.model",
                    "AI model not downloaded yet",
                    "We need to download " + model + " before we can analyze your emails. "
                            + (installed.isEmpty()
                                ? "No models are installed yet."
                                : "Other models found: " + String.join(", ", installed)),
                    "We can download it for you — it's about 2 GB, one-time. "
                            + "After this, future launches start instantly. "
                            + "Just click the button below.")
                    .withFixCommand("ollama pull " + model)
                    .withAutoFix("pull-model");
        } catch (Exception e) {
            return DiagnosticCheck.warn("llm.model",
                    "Can't tell if the AI model is ready",
                    "Couldn't check Ollama's installed models.",
                    "This usually clears up once Ollama is running. Re-check after starting it.");
        }
    }

    /** Did we manage to attach to a real Chrome at boot? */
    private DiagnosticCheck checkChromeAttached() {
        BrowserTool browser = browserToolProvider.getIfAvailable();
        if (browser == null) {
            return DiagnosticCheck.fail("chrome.attached",
                    "Chrome browser not connected",
                    "Intelli-mailbox needs to attach to a real Chrome window to read your Gmail.",
                    "Close Intelli-mailbox completely and open it again. The first launch creates "
                            + "a dedicated Chrome window — make sure your antivirus isn't blocking it.");
        }
        return DiagnosticCheck.ok("chrome.attached",
                "Chrome is connected",
                "Intelli-mailbox is talking to its Chrome window.");
    }

    /**
     * Best-effort probe for "the user has signed in to Gmail in the attached
     * Chrome". We can't tell apart "not signed in" from "still loading", so
     * a {@code warn} is friendlier than {@code fail}.
     */
    private DiagnosticCheck checkGmailSignedIn() {
        BrowserTool browser = browserToolProvider.getIfAvailable();
        if (browser == null) {
            return DiagnosticCheck.warn("gmail.signedIn",
                    "Gmail status unknown",
                    "We can check this once the Chrome connection is up.",
                    "");
        }
        try {
            // Look for any element that only renders inside a logged-in Gmail view —
            // the inbox row table is the most reliable signal.
            String probe = "(() => {"
                    + "  if (!location.hostname.includes('mail.google.com')) return 'off-gmail';"
                    + "  if (location.hash.includes('signin') || location.pathname.includes('signin')) return 'signin';"
                    + "  if (document.querySelector('[role=\"main\"] [role=\"row\"]')) return 'in';"
                    + "  return 'unknown';"
                    + "})()";
            Object r = browser.execute(Map.of("operation", "evaluate_js", "code", probe));
            String state = String.valueOf(r);
            return switch (state) {
                case "in" -> DiagnosticCheck.ok("gmail.signedIn",
                        "Signed in to Gmail",
                        "We can see your Gmail inbox.");
                case "signin", "off-gmail" -> DiagnosticCheck.warn("gmail.signedIn",
                        "Sign in to Gmail to continue",
                        "Switch to the Chrome window Intelli-mailbox opened and sign in to your Google account.",
                        "Look for a Chrome window titled \"Intelli-mailbox\" or \"Gmail\". "
                                + "If you closed it, click the button below — we'll reopen it. "
                                + "Sign in once and the session sticks across launches.")
                        .withAutoFix("open-gmail");
                default -> DiagnosticCheck.warn("gmail.signedIn",
                        "Gmail status uncertain",
                        "Gmail might still be loading, or the page state is unfamiliar.",
                        "Give it a few seconds and click Re-check. If it stays stuck, "
                                + "switch to the Chrome window and click Refresh there.");
            };
        } catch (Exception e) {
            return DiagnosticCheck.warn("gmail.signedIn",
                    "Couldn't check Gmail",
                    "We had trouble talking to the Chrome window.",
                    "This usually clears up if you click Re-check in a few seconds.");
        }
    }
}
