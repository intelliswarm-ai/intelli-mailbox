package ai.intelliswarm.intellimailbox.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The registry of LLM providers Intelli-mailbox knows how to talk to.
 *
 * <p>Adding a new provider is a one-line change here. The {@link #boot()}
 * routing in {@link SettingsStore} keys off {@link LlmTransport}, so any
 * cloud provider that exposes an OpenAI-compatible chat-completions API
 * (Groq, DeepSeek, Mistral, OpenRouter, Azure, LM Studio, LocalAI, …)
 * "just works" by registering it here with {@code transport = OPENAI} and
 * the right {@code defaultBaseUrl}.
 */
public final class ProviderCatalog {

    private ProviderCatalog() { }

    private static final List<LlmProvider> PROVIDERS = List.of(
            new LlmProvider(
                    "ollama", "Ollama", "Local LLM runtime — emails never leave your machine.",
                    LlmTransport.OLLAMA, "local",
                    "http://localhost:11434", false,
                    "https://ollama.com", null, "https://ollama.com/library",
                    "qwen2.5:3b",
                    List.of("qwen2.5:1.5b", "qwen2.5:3b", "qwen2.5:7b", "llama3.2:3b",
                            "llama3.1:8b", "phi3:mini", "mistral:7b", "gemma2:9b"),
                    "🧠",
                    "Install Ollama, then run `ollama pull <model>` to download weights. " +
                            "The Ollama tab shows install commands for your OS and recommends " +
                            "models that fit your RAM."
            ),
            new LlmProvider(
                    "openai", "OpenAI",
                    "GPT-4o, GPT-4o-mini, o-series. Email content is sent to api.openai.com.",
                    LlmTransport.OPENAI, "cloud",
                    "https://api.openai.com", true,
                    "https://openai.com", "https://platform.openai.com/api-keys",
                    "https://platform.openai.com/docs/models",
                    "gpt-4o-mini",
                    List.of("gpt-4o-mini", "gpt-4o", "gpt-4.1", "gpt-4.1-mini", "o4-mini"),
                    "☁",
                    "Get an API key at platform.openai.com/api-keys. " +
                            "gpt-4o-mini is the cost-friendly default (~$0.0005 per email)."
            ),
            new LlmProvider(
                    "anthropic", "Anthropic Claude",
                    "Claude Sonnet / Opus / Haiku. Email content is sent to api.anthropic.com.",
                    LlmTransport.ANTHROPIC, "cloud",
                    "https://api.anthropic.com", true,
                    "https://anthropic.com", "https://console.anthropic.com/settings/keys",
                    "https://docs.anthropic.com/en/docs/about-claude/models/overview",
                    "claude-haiku-4-5",
                    List.of("claude-haiku-4-5", "claude-sonnet-4-6", "claude-opus-4-7",
                            "claude-3-5-haiku-latest", "claude-3-5-sonnet-latest"),
                    "🔮",
                    "Get an API key at console.anthropic.com/settings/keys. " +
                            "claude-haiku-4-5 is the cost-friendly default; sonnet for higher quality."
            ),
            new LlmProvider(
                    "groq", "Groq",
                    "OpenAI-compatible · ultra-fast inference for Llama / Mixtral / Qwen on LPUs.",
                    LlmTransport.OPENAI, "cloud",
                    "https://api.groq.com/openai/v1", true,
                    "https://groq.com", "https://console.groq.com/keys",
                    "https://console.groq.com/docs/models",
                    "llama-3.3-70b-versatile",
                    List.of("llama-3.3-70b-versatile", "llama-3.1-8b-instant",
                            "mixtral-8x7b-32768", "qwen-2.5-32b", "deepseek-r1-distill-llama-70b"),
                    "⚡",
                    "Get a key at console.groq.com/keys. Groq exposes a drop-in OpenAI-compatible API."
            ),
            new LlmProvider(
                    "mistral", "Mistral AI",
                    "Mistral / Codestral / Pixtral via the OpenAI-compatible API at api.mistral.ai.",
                    LlmTransport.OPENAI, "cloud",
                    "https://api.mistral.ai/v1", true,
                    "https://mistral.ai", "https://console.mistral.ai/api-keys/",
                    "https://docs.mistral.ai/getting-started/models/models_overview/",
                    "mistral-small-latest",
                    List.of("mistral-small-latest", "mistral-large-latest",
                            "open-mistral-nemo", "ministral-8b-latest", "codestral-latest"),
                    "🌬",
                    "Get a key at console.mistral.ai/api-keys/. Use the OpenAI-compatible base URL above."
            ),
            new LlmProvider(
                    "deepseek", "DeepSeek",
                    "DeepSeek-V3 / R1 via OpenAI-compatible API — strong reasoning, low cost.",
                    LlmTransport.OPENAI, "cloud",
                    "https://api.deepseek.com/v1", true,
                    "https://deepseek.com", "https://platform.deepseek.com/api_keys",
                    "https://api-docs.deepseek.com/quick_start/pricing",
                    "deepseek-chat",
                    List.of("deepseek-chat", "deepseek-reasoner"),
                    "🐋",
                    "Get a key at platform.deepseek.com/api_keys. deepseek-chat is V3, deepseek-reasoner is R1."
            ),
            new LlmProvider(
                    "openrouter", "OpenRouter",
                    "300+ models behind one OpenAI-compatible API — including OSS models and frontier providers.",
                    LlmTransport.OPENAI, "cloud",
                    "https://openrouter.ai/api/v1", true,
                    "https://openrouter.ai", "https://openrouter.ai/keys",
                    "https://openrouter.ai/models",
                    "anthropic/claude-3.5-haiku",
                    List.of("anthropic/claude-3.5-haiku", "openai/gpt-4o-mini",
                            "google/gemini-2.0-flash-001", "meta-llama/llama-3.3-70b-instruct",
                            "qwen/qwen-2.5-72b-instruct", "deepseek/deepseek-chat"),
                    "🛣",
                    "Get a key at openrouter.ai/keys. Models use 'vendor/model' slugs; browse openrouter.ai/models."
            ),
            new LlmProvider(
                    "azure-openai", "Azure OpenAI",
                    "OpenAI models hosted in your Azure tenant. Set base URL to your resource endpoint.",
                    LlmTransport.OPENAI, "cloud",
                    "https://YOUR-RESOURCE.openai.azure.com/openai/deployments/YOUR-DEPLOYMENT", true,
                    "https://azure.microsoft.com/en-us/products/ai-services/openai-service",
                    "https://portal.azure.com",
                    "https://learn.microsoft.com/en-us/azure/ai-services/openai/concepts/models",
                    "gpt-4o-mini",
                    List.of("gpt-4o-mini", "gpt-4o", "gpt-4.1", "gpt-4.1-mini"),
                    "🟦",
                    "The model field must match your Azure 'deployment name'. " +
                            "Replace the placeholders in the base URL with your resource + deployment name."
            ),
            new LlmProvider(
                    "lm-studio", "LM Studio",
                    "Local desktop app · runs OSS models with an OpenAI-compatible server on :1234.",
                    LlmTransport.OPENAI, "self-hosted",
                    "http://localhost:1234/v1", false,
                    "https://lmstudio.ai", null, "https://lmstudio.ai/models",
                    "qwen2.5-7b-instruct",
                    List.of("qwen2.5-7b-instruct", "llama-3.2-3b-instruct", "mistral-7b-instruct-v0.3"),
                    "🖥",
                    "Install LM Studio, download a model, and start the local server (Developer → Start Server). " +
                            "The model name must match the loaded model's identifier in LM Studio."
            ),
            new LlmProvider(
                    "localai", "LocalAI",
                    "Self-hosted, OpenAI-compatible · runs in Docker on your own infra.",
                    LlmTransport.OPENAI, "self-hosted",
                    "http://localhost:8080/v1", false,
                    "https://localai.io", null, "https://localai.io/gallery.html",
                    "gpt-4",
                    List.of("gpt-4", "gpt-3.5-turbo"),
                    "🐳",
                    "Run with `docker run -p 8080:8080 localai/localai`. " +
                            "Models are configured server-side; the client just picks one by name."
            ),
            new LlmProvider(
                    "openai-compatible", "Custom (OpenAI-compatible)",
                    "Any other OpenAI-compatible endpoint — Together AI, Fireworks, vLLM, llama.cpp server, …",
                    LlmTransport.OPENAI, "custom",
                    "", false,
                    null, null, null,
                    "",
                    List.of(),
                    "✦",
                    "Set the base URL to whatever exposes the OpenAI /v1/chat/completions schema. " +
                            "Provide an API key only if your endpoint requires one."
            )
    );

    public static List<LlmProvider> all() {
        return PROVIDERS;
    }

    public static Optional<LlmProvider> byId(String id) {
        if (id == null) return Optional.empty();
        return PROVIDERS.stream().filter(p -> p.id().equalsIgnoreCase(id)).findFirst();
    }

    /** Default ID = the privacy-by-default local provider. */
    public static String defaultProviderId() {
        return "ollama";
    }

    /**
     * Empty-config seed map keyed by provider id, in catalog order. Settings
     * are saved as a sparse map but the API surface returns this fully
     * populated so the UI can render each tab regardless of save state.
     */
    public static Map<String, ProviderConfig> emptyConfigs() {
        Map<String, ProviderConfig> out = new LinkedHashMap<>();
        for (LlmProvider p : PROVIDERS) {
            out.put(p.id(), new ProviderConfig(p.defaultBaseUrl(), "", p.defaultModel()));
        }
        return out;
    }
}
