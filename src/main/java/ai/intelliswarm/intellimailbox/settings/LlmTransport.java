package ai.intelliswarm.intellimailbox.settings;

/**
 * How Spring AI should talk to the configured LLM.
 *
 * <p>Each transport corresponds to a Spring AI starter and an
 * {@code application-<profile>.yml} file. The {@link ProviderCatalog} can
 * route many user-facing providers (Groq, DeepSeek, Azure OpenAI, …) onto
 * the same {@code OPENAI} transport because they all expose an
 * OpenAI-compatible chat-completions endpoint — only the base URL changes.
 */
public enum LlmTransport {
    OLLAMA,
    OPENAI,
    ANTHROPIC
}
