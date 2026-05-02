package ai.intelliswarm.intellimailbox.settings;

import java.util.List;

/**
 * Static, public metadata describing one LLM provider the user can pick.
 *
 * <p>Multiple providers can share the same {@link LlmTransport} — e.g. Groq,
 * DeepSeek, Azure OpenAI, OpenRouter, LM Studio, LocalAI and "Custom" all
 * use {@link LlmTransport#OPENAI} (Spring AI's OpenAI client) with a
 * different {@code defaultBaseUrl}. That's how we support every major
 * "OpenAI-compatible" endpoint without adding starters.
 *
 * <p>{@code kind} surfaces the privacy posture in the UI ("local" =
 * everything stays on this machine, "cloud" = network call, "self-hosted"
 * = local but compatible with cloud APIs).
 */
public record LlmProvider(
        String id,
        String label,
        String description,
        LlmTransport transport,
        String kind,                 // "local" | "cloud" | "self-hosted" | "custom"
        String defaultBaseUrl,
        boolean requiresApiKey,
        String homepage,
        String signupUrl,
        String modelCatalogUrl,
        String defaultModel,
        List<String> suggestedModels,
        String icon,
        String setupNotes
) {
    public boolean isLocal() {
        return "local".equals(kind) || "self-hosted".equals(kind);
    }
}
