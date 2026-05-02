package ai.intelliswarm.intellimailbox.settings;

/**
 * Per-provider configuration the user has filled in. All fields are optional —
 * a provider entry exists in {@link LlmSettings#providers()} as soon as the
 * user touches its form, even if no fields are set.
 *
 * <p>{@code apiKey} is empty for local providers (Ollama, LM Studio, LocalAI).
 * {@code baseUrl} is allowed to be empty too — when blank, the bootstrap
 * falls back to the {@link LlmProvider#defaultBaseUrl()} from the catalog.
 */
public record ProviderConfig(
        String baseUrl,
        String apiKey,
        String model
) {
    public static ProviderConfig empty() {
        return new ProviderConfig("", "", "");
    }

    public ProviderConfig withBaseUrl(String b)  { return new ProviderConfig(b == null ? "" : b, apiKey, model); }
    public ProviderConfig withApiKey(String k)   { return new ProviderConfig(baseUrl, k == null ? "" : k, model); }
    public ProviderConfig withModel(String m)    { return new ProviderConfig(baseUrl, apiKey, m == null ? "" : m); }

    public boolean hasApiKey() { return apiKey != null && !apiKey.isBlank(); }
    public boolean hasModel()  { return model != null && !model.isBlank(); }
}
