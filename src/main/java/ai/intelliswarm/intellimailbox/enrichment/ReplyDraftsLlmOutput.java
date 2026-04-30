package ai.intelliswarm.intellimailbox.enrichment;

/**
 * Mutable POJO for Spring AI's {@code BeanOutputConverter} to deserialize the
 * second-pass LLM call into. The framework prefers public-field POJOs over
 * records when generating structured output, so we use one and convert to the
 * immutable {@link ReplyDrafts} record before exposing to the rest of the app.
 */
public class ReplyDraftsLlmOutput {
    /** Professional, polite, suitable for client / external comms. */
    public String formal;
    /** Warm, first-person, suitable for teammates / familiar contacts. */
    public String friendly;
    /** 1–2 sentences, very direct, suitable for "ack" replies. */
    public String brief;
}
