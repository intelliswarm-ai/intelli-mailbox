package ai.intelliswarm.intellimailbox.settings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The persisted user choices: which provider is active and how each provider
 * is configured.
 *
 * <p>Saved as JSON to {@code ~/.intelliswarm/intellimailbox/settings.json}
 * by {@link SettingsStore}. Any explicit {@code -D…} / env-var override at
 * launch still wins — see {@link SettingsStore#applyAsSystemProperties()}.
 */
public record LlmSettings(
        String activeProvider,
        Map<String, ProviderConfig> providers,
        /**
         * Output language for AI-generated text (summary, ctas, replies).
         * "auto" → match the email's own language (legacy behaviour).
         * Otherwise an ISO 639-1 code: "en", "el", "es", "fr", "de", "it",
         * "pt", "nl", "ja", "zh", "ru", "ar", "hi", "tr", "pl", "sv".
         */
        String language,
        /**
         * When {@code true}, the UI surfaces a "Debug" view tab that shows the
         * raw scraped data per email (InboxItem metadata + verbatim body the
         * AI received). UI-only — has no effect on enrichment. Off by default
         * because power-user surface.
         */
        boolean debugMode
) {
    /** Backwards-compatible canonical constructor: default language to "auto" when null/blank. */
    public LlmSettings {
        if (language == null || language.isBlank()) language = "auto";
    }

    public static LlmSettings defaults() {
        return new LlmSettings(
                ProviderCatalog.defaultProviderId(),
                ProviderCatalog.emptyConfigs(),
                "auto",
                false
        );
    }

    /**
     * Returns the config for {@code id}, falling back to the catalog defaults
     * (default base URL, empty key, default model). Never returns {@code null}.
     */
    public ProviderConfig configFor(String id) {
        ProviderConfig c = providers == null ? null : providers.get(id);
        if (c != null) return c;
        return ProviderCatalog.byId(id)
                .map(p -> new ProviderConfig(p.defaultBaseUrl(), "", p.defaultModel()))
                .orElse(ProviderConfig.empty());
    }

    /** Return a copy of {@code providers} backed by a fresh LinkedHashMap (preserves order). */
    public Map<String, ProviderConfig> providersCopy() {
        Map<String, ProviderConfig> out = new LinkedHashMap<>();
        if (providers != null) out.putAll(providers);
        return out;
    }
}
