package ai.intelliswarm.intellimailbox.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic settings API.
 *
 * <pre>
 *   GET  /api/settings                             current state + full provider catalog + system info
 *   POST /api/settings                             persist activeProvider + per-provider configs
 *   GET  /api/settings/ollama/tags?baseUrl=…       live "what models are pulled?" probe
 *   GET  /api/settings/ping?providerId=…           generic reachability probe (any provider)
 * </pre>
 *
 * <p>Transparency: the GET response always echoes the resolved base URL,
 * the settings file path, the active Spring profile, and the env-var names
 * a power user could override on the command line.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final Logger logger = LoggerFactory.getLogger(SettingsController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    /** Sentinel the UI sends back when the user didn't edit the masked api-key field. */
    private static final String KEY_MASK_GLYPH = "…";

    @GetMapping
    public Map<String, Object> get() {
        LlmSettings s = SettingsStore.load();

        // Provider catalog (static metadata) plus the user's per-provider config (key masked).
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (LlmProvider p : ProviderCatalog.all()) {
            ProviderConfig cfg = s.configFor(p.id());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", p.id());
            entry.put("label", p.label());
            entry.put("description", p.description());
            entry.put("transport", p.transport().name());
            entry.put("kind", p.kind());
            entry.put("isLocal", p.isLocal());
            entry.put("requiresApiKey", p.requiresApiKey());
            entry.put("defaultBaseUrl", p.defaultBaseUrl());
            entry.put("defaultModel", p.defaultModel());
            entry.put("suggestedModels", p.suggestedModels());
            entry.put("homepage", p.homepage());
            entry.put("signupUrl", p.signupUrl());
            entry.put("modelCatalogUrl", p.modelCatalogUrl());
            entry.put("icon", p.icon());
            entry.put("setupNotes", p.setupNotes());
            entry.put("envVars", envVarsFor(p));

            // The user's saved config for this provider (api key masked on the wire).
            Map<String, Object> userCfg = new LinkedHashMap<>();
            userCfg.put("baseUrl", cfg.baseUrl() == null ? "" : cfg.baseUrl());
            userCfg.put("model",   cfg.model() == null ? "" : cfg.model());
            userCfg.put("apiKeyMasked", maskKey(cfg.apiKey()));
            userCfg.put("apiKeySet", cfg.hasApiKey());
            userCfg.put("configured", isConfigured(p, cfg));
            entry.put("config", userCfg);
            catalog.add(entry);
        }

        Map<String, Object> active = activeSummary(s);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeProvider", s.activeProvider());
        out.put("active", active);
        out.put("providers", catalog);
        out.put("language", s.language());
        out.put("languageCatalog", LANGUAGE_CATALOG);
        out.put("settingsPath", SettingsStore.path().toString());
        out.put("activeProfile", System.getProperty("spring.profiles.active", "ollama"));
        out.put("system", SystemInfo.snapshot());
        return out;
    }

    /** Small, hand-curated list of output languages. The user can also pass any
     *  ISO 639-1 code we don't list; we don't reject unknown codes server-side. */
    private static final List<Map<String, String>> LANGUAGE_CATALOG = List.of(
            Map.of("code", "auto", "label", "Auto · match the email's language"),
            Map.of("code", "en",   "label", "English"),
            Map.of("code", "el",   "label", "Ελληνικά (Greek)"),
            Map.of("code", "es",   "label", "Español (Spanish)"),
            Map.of("code", "fr",   "label", "Français (French)"),
            Map.of("code", "de",   "label", "Deutsch (German)"),
            Map.of("code", "it",   "label", "Italiano (Italian)"),
            Map.of("code", "pt",   "label", "Português (Portuguese)"),
            Map.of("code", "nl",   "label", "Nederlands (Dutch)"),
            Map.of("code", "pl",   "label", "Polski (Polish)"),
            Map.of("code", "sv",   "label", "Svenska (Swedish)"),
            Map.of("code", "tr",   "label", "Türkçe (Turkish)"),
            Map.of("code", "ru",   "label", "Русский (Russian)"),
            Map.of("code", "uk",   "label", "Українська (Ukrainian)"),
            Map.of("code", "ar",   "label", "العربية (Arabic)"),
            Map.of("code", "he",   "label", "עברית (Hebrew)"),
            Map.of("code", "hi",   "label", "हिन्दी (Hindi)"),
            Map.of("code", "ja",   "label", "日本語 (Japanese)"),
            Map.of("code", "ko",   "label", "한국어 (Korean)"),
            Map.of("code", "zh",   "label", "中文 (Chinese)")
    );

    @PostMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new HashMap<>();
        try {
            LlmSettings current = SettingsStore.load();

            String activeProvider = strOr(body, "activeProvider", current.activeProvider()).toLowerCase();
            if (ProviderCatalog.byId(activeProvider).isEmpty()) {
                resp.put("ok", false);
                resp.put("reason", "unknown providerId: " + activeProvider);
                return ResponseEntity.ok(resp);
            }

            // Merge submitted provider configs over the current ones. The UI sends
            // only the providers the user touched; we preserve everything else.
            Map<String, ProviderConfig> next = current.providersCopy();
            Object rawProviders = body.get("providers");
            if (rawProviders instanceof Map<?, ?> providersMap) {
                for (Map.Entry<?, ?> e : providersMap.entrySet()) {
                    String id = String.valueOf(e.getKey()).toLowerCase();
                    if (ProviderCatalog.byId(id).isEmpty()) continue; // ignore unknown ids
                    if (!(e.getValue() instanceof Map<?, ?> cfgMap)) continue;
                    ProviderConfig prev = next.getOrDefault(id, ProviderConfig.empty());
                    String submittedKey = strOr((Map<String, Object>) cfgMap, "apiKey", null);
                    String mergedKey = isUnchangedMaskedKey(submittedKey) ? prev.apiKey() : submittedKey.trim();
                    ProviderConfig merged = new ProviderConfig(
                            strOr((Map<String, Object>) cfgMap, "baseUrl", prev.baseUrl()).trim(),
                            mergedKey,
                            strOr((Map<String, Object>) cfgMap, "model", prev.model()).trim()
                    );
                    next.put(id, merged);
                }
            }

            // Language is optional in the request body — keep what's saved if absent / blank.
            String submittedLang = strOr(body, "language", current.language());
            String language = (submittedLang == null || submittedLang.isBlank())
                    ? current.language()
                    : submittedLang.trim().toLowerCase();

            LlmSettings saved = new LlmSettings(activeProvider, next, language);
            SettingsStore.save(saved);
            // Language is the one setting that takes effect immediately —
            // EnrichmentService reads it via System.getProperty on every prompt
            // build, so updating the system property here means the next email
            // enriched (or re-processed) uses the new language without a restart.
            // Provider / model / base URL still require a restart because Spring
            // AI's ChatClient is wired at boot.
            if (language != null && !language.isBlank()) {
                System.setProperty("intellimailbox.language", language);
            }
            logger.info("Settings saved: activeProvider={}, providers configured={}",
                    activeProvider, next.entrySet().stream()
                            .filter(en -> en.getValue() != null
                                    && (en.getValue().hasApiKey() || en.getValue().hasModel()))
                            .map(Map.Entry::getKey).toList());
            resp.put("ok", true);
            resp.put("restartRequired", true);
            resp.put("savedTo", SettingsStore.path().toString());
            resp.put("active", activeSummary(saved));
            return ResponseEntity.ok(resp);
        } catch (Throwable t) {
            logger.warn("POST /api/settings failed: {}", t.toString(), t);
            resp.put("ok", false);
            resp.put("reason", t.getClass().getSimpleName() + ": " + t.getMessage());
            return ResponseEntity.ok(resp);
        }
    }

    /**
     * Live probe of an Ollama base URL — calls {@code /api/tags} so the UI can
     * show "reachable" + the list of models the user has already pulled.
     */
    @GetMapping("/ollama/tags")
    public Map<String, Object> ollamaTags(@RequestParam(value = "baseUrl", required = false) String baseUrl) {
        LlmSettings s = SettingsStore.load();
        ProviderConfig cfg = s.configFor("ollama");
        String fallback = cfg.baseUrl().isBlank()
                ? ProviderCatalog.byId("ollama").orElseThrow().defaultBaseUrl()
                : cfg.baseUrl();
        String base = (baseUrl == null || baseUrl.isBlank()) ? fallback : baseUrl.trim();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseUrl", base);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base.replaceAll("/+$", "") + "/api/tags"))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                out.put("reachable", false);
                out.put("status", resp.statusCode());
                return out;
            }
            JsonNode root = MAPPER.readTree(resp.body());
            List<Map<String, Object>> models = new ArrayList<>();
            JsonNode arr = root.path("models");
            if (arr.isArray()) {
                for (JsonNode m : arr) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", m.path("name").asText(""));
                    entry.put("sizeBytes", m.path("size").asLong(0));
                    entry.put("modifiedAt", m.path("modified_at").asText(""));
                    models.add(entry);
                }
            }
            out.put("reachable", true);
            out.put("models", models);
        } catch (Exception e) {
            out.put("reachable", false);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }

    /**
     * Generic reachability probe for any provider. For Ollama we hit /api/tags;
     * for everything else we issue a short HEAD/GET against the base URL just
     * to verify DNS + TCP. We don't authenticate — we don't want to spend the
     * user's tokens on a connectivity ping.
     */
    @GetMapping("/ping/{providerId}")
    public Map<String, Object> ping(
            @PathVariable("providerId") String providerId,
            @RequestParam(value = "baseUrl", required = false) String baseUrl) {
        LlmProvider provider = ProviderCatalog.byId(providerId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("providerId", providerId);
        if (provider == null) {
            out.put("reachable", false);
            out.put("error", "unknown providerId");
            return out;
        }
        LlmSettings s = SettingsStore.load();
        ProviderConfig cfg = s.configFor(providerId);
        String base = (baseUrl == null || baseUrl.isBlank())
                ? (cfg.baseUrl().isBlank() ? provider.defaultBaseUrl() : cfg.baseUrl())
                : baseUrl.trim();
        out.put("baseUrl", base);
        if (base == null || base.isBlank()) {
            out.put("reachable", false);
            out.put("error", "no base URL configured");
            return out;
        }
        if (provider.transport() == LlmTransport.OLLAMA) {
            return ollamaTags(base); // richer info for Ollama
        }
        try {
            // GET / on the base URL — most providers return 200/401/404 quickly
            // (we just want to know that DNS + TCP work, not that we're authenticated).
            URI uri = URI.create(base);
            HttpRequest req = HttpRequest.newBuilder().uri(uri)
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            int code = resp.statusCode();
            out.put("reachable", true);
            out.put("status", code);
            // 401/403 from a cloud provider just means "you didn't auth" — that's fine, host is up.
            out.put("authChallenged", code == 401 || code == 403);
            return out;
        } catch (Exception e) {
            out.put("reachable", false);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return out;
        }
    }

    // --- helpers ----------------------------------------------------------

    /** Echoes the resolved active provider so the UI's "Local · model" pill is computed once, server-side. */
    private static Map<String, Object> activeSummary(LlmSettings s) {
        LlmProvider p = ProviderCatalog.byId(s.activeProvider())
                .orElseGet(() -> ProviderCatalog.byId(ProviderCatalog.defaultProviderId()).orElseThrow());
        ProviderConfig cfg = s.configFor(p.id());
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", p.id());
        a.put("label", p.label());
        a.put("transport", p.transport().name());
        a.put("kind", p.kind());
        a.put("isLocal", p.isLocal());
        a.put("icon", p.icon());
        a.put("baseUrl", cfg.baseUrl().isBlank() ? p.defaultBaseUrl() : cfg.baseUrl());
        a.put("model", cfg.model().isBlank() ? p.defaultModel() : cfg.model());
        a.put("apiKeySet", cfg.hasApiKey());
        a.put("ready", isConfigured(p, cfg));
        return a;
    }

    private static boolean isConfigured(LlmProvider p, ProviderConfig cfg) {
        // "Configured" = at least the model is set, and (if cloud + key required) a key exists.
        boolean hasModel = !cfg.model().isBlank() || !p.defaultModel().isBlank();
        boolean keyOk = !p.requiresApiKey() || cfg.hasApiKey();
        return hasModel && keyOk;
    }

    /** Returns the env-var names that override this provider's fields — surfaced in the UI for transparency. */
    private static Map<String, String> envVarsFor(LlmProvider p) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("profile", "SPRING_PROFILES_ACTIVE");
        switch (p.transport()) {
            case OLLAMA -> {
                e.put("baseUrl", "SPRING_AI_OLLAMA_BASE_URL");
                e.put("model",   "SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL");
            }
            case OPENAI -> {
                e.put("apiKey",  "OPENAI_API_KEY");
                e.put("baseUrl", "SPRING_AI_OPENAI_BASE_URL");
                e.put("model",   "SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL");
            }
            case ANTHROPIC -> {
                e.put("apiKey",  "ANTHROPIC_API_KEY");
                e.put("baseUrl", "SPRING_AI_ANTHROPIC_BASE_URL");
                e.put("model",   "SPRING_AI_ANTHROPIC_CHAT_OPTIONS_MODEL");
            }
        }
        return e;
    }

    private static boolean isUnchangedMaskedKey(String submitted) {
        // Treat empty / null / pure asterisks / anything containing the mask glyph as "no change".
        return submitted == null
                || submitted.isBlank()
                || submitted.contains(KEY_MASK_GLYPH)
                || submitted.matches("\\*+");
    }

    @SuppressWarnings("unchecked")
    private static String strOr(Map<String, Object> m, String k, String dflt) {
        if (m == null) return dflt;
        Object v = m.get(k);
        return v == null ? dflt : String.valueOf(v);
    }

    private static String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 8) return "********";
        return key.substring(0, 3) + KEY_MASK_GLYPH + key.substring(key.length() - 4);
    }
}
