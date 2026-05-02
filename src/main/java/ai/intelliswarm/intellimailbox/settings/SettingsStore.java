package ai.intelliswarm.intellimailbox.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Reads / writes the user's LLM settings as JSON.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #applyAsSystemProperties()} — invoked from {@code main()} before
 *       Spring starts. Translates the active-provider config into the same
 *       system properties that {@code application-<profile>.yml} reads via
 *       {@code ${ENV_VAR:default}} placeholders, picking the right Spring
 *       profile for the transport.</li>
 *   <li>{@link #load()} / {@link #save(LlmSettings)} — used by the REST controller.</li>
 * </ul>
 *
 * <p>Setting precedence at boot:
 * <ol>
 *   <li>{@code -D…} JVM args / environment variables (highest)</li>
 *   <li>The active provider's saved config from this store</li>
 *   <li>The catalog's {@link LlmProvider#defaultBaseUrl()} / {@link LlmProvider#defaultModel()}</li>
 *   <li>YAML defaults inside {@code application-*.yml} (lowest)</li>
 * </ol>
 */
public final class SettingsStore {

    private static final Logger logger = LoggerFactory.getLogger(SettingsStore.class);

    private static final Path SETTINGS_PATH = Paths.get(
            System.getProperty("user.home"), ".intelliswarm", "intellimailbox", "settings.json");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private SettingsStore() { }

    public static Path path() { return SETTINGS_PATH; }

    public static LlmSettings load() {
        if (!Files.exists(SETTINGS_PATH)) return LlmSettings.defaults();
        try {
            LlmSettings parsed = MAPPER.readValue(SETTINGS_PATH.toFile(), LlmSettings.class);
            // Backfill any provider that's been added to the catalog since the
            // file was written, so the UI always sees an entry per provider.
            var merged = ProviderCatalog.emptyConfigs();
            if (parsed.providers() != null) merged.putAll(parsed.providersCopy());
            String active = (parsed.activeProvider() == null || parsed.activeProvider().isBlank())
                    ? ProviderCatalog.defaultProviderId()
                    : parsed.activeProvider();
            return new LlmSettings(active, merged);
        } catch (IOException e) {
            logger.warn("Failed to read {} — falling back to defaults: {}", SETTINGS_PATH, e.toString());
            return LlmSettings.defaults();
        }
    }

    public static void save(LlmSettings s) throws IOException {
        Files.createDirectories(SETTINGS_PATH.getParent());
        MAPPER.writeValue(SETTINGS_PATH.toFile(), s);
        tightenPermissions(SETTINGS_PATH);
    }

    /**
     * Best-effort 0600 on POSIX so a plaintext API key can't be read by other
     * users on the same machine. Silent no-op on Windows / non-POSIX FS.
     */
    private static void tightenPermissions(Path p) {
        try {
            Files.setPosixFilePermissions(p, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / non-POSIX — file is in the user's home dir, default ACLs already restrict it.
        }
    }

    /**
     * Translate the active provider's config into Spring system properties.
     * Each {@code setIfAbsent} respects existing {@code -D} args / env vars,
     * so a power user can still pin any field on the launch command line.
     */
    public static void applyAsSystemProperties() {
        LlmSettings settings = load();
        LlmProvider provider = ProviderCatalog.byId(settings.activeProvider())
                .orElseGet(() -> ProviderCatalog.byId(ProviderCatalog.defaultProviderId()).orElseThrow());
        ProviderConfig cfg = settings.configFor(provider.id());

        String baseUrl = firstNonBlank(cfg.baseUrl(), provider.defaultBaseUrl());
        String model   = firstNonBlank(cfg.model(),   provider.defaultModel());
        String apiKey  = cfg.apiKey() == null ? "" : cfg.apiKey();

        switch (provider.transport()) {
            case OLLAMA -> {
                setIfAbsent("spring.profiles.active",                "ollama");
                setIfAbsent("SPRING_AI_OLLAMA_BASE_URL",             baseUrl);
                setIfAbsent("SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL",   model);
            }
            case OPENAI -> {
                // Same Spring profile + starter for OpenAI and every OpenAI-compatible
                // provider (Groq, DeepSeek, Mistral, OpenRouter, Azure, LM Studio, …).
                // Only the base-url + key differ per provider.
                setIfAbsent("spring.profiles.active",                "openai-mini");
                setIfAbsent("OPENAI_API_KEY",                        apiKey);
                setIfAbsent("SPRING_AI_OPENAI_BASE_URL",             baseUrl);
                setIfAbsent("SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL",   model);
            }
            case ANTHROPIC -> {
                setIfAbsent("spring.profiles.active",                "anthropic");
                setIfAbsent("ANTHROPIC_API_KEY",                     apiKey);
                setIfAbsent("SPRING_AI_ANTHROPIC_BASE_URL",          baseUrl);
                setIfAbsent("SPRING_AI_ANTHROPIC_CHAT_OPTIONS_MODEL", model);
            }
        }

        logger.info("LLM settings: active={} ({}), transport={}, baseUrl={}, model={}, hasKey={}",
                provider.id(), provider.kind(), provider.transport(),
                baseUrl, model, !apiKey.isBlank());
    }

    private static String firstNonBlank(String... s) {
        for (String x : s) if (x != null && !x.isBlank()) return x;
        return "";
    }

    private static void setIfAbsent(String k, String v) {
        if (v == null || v.isBlank()) return;
        if (System.getProperty(k) == null && System.getenv(k) == null) {
            System.setProperty(k, v);
        }
    }
}
