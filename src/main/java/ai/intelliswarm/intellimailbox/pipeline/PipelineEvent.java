package ai.intelliswarm.intellimailbox.pipeline;

import ai.intelliswarm.intellimailbox.enrichment.EnrichedEmail;

/**
 * Events emitted by {@link PreprocessingPipeline} into the SSE channel.
 *
 * <p>Sealed so the controller layer can {@code switch} exhaustively when
 * mapping subtypes to SSE event names.
 *
 * <ul>
 *   <li>{@link SummaryDelta} — one per LLM token (or chunk) of the streaming
 *       summary pass, carrying the email row id so the UI can append into the
 *       correct card. Many of these per email.</li>
 *   <li>{@link Enriched} — the terminal event for one email: the fully-built
 *       {@link EnrichedEmail} with summary, badges, CTAs, optional drafts.
 *       Exactly one per email.</li>
 * </ul>
 *
 * <p>Wire format (SSE):
 * <pre>
 *   event: summary_delta
 *   data:  {"emailId":"5","text":"The "}
 *
 *   event: summary_delta
 *   data:  {"emailId":"5","text":"sender "}
 *
 *   event: email
 *   data:  {"emailId":"5","email":{ ... full EnrichedEmail JSON ... }}
 * </pre>
 *
 * @since 1.1.0  (token-level streaming for per-email enrichment)
 */
public sealed interface PipelineEvent {

    /** Inbox-row id this event is about — UI keys by this to demux per card. */
    String emailId();

    /**
     * Streaming summary delta — one prose token (or small chunk) of the
     * email's summary. Concatenating all deltas in order reconstructs the
     * full {@code summary} field of the eventual {@link EnrichedEmail}.
     */
    record SummaryDelta(String emailId, String text) implements PipelineEvent {}

    /**
     * Terminal event for one email — the fully-built {@link EnrichedEmail}
     * containing the same summary text the deltas streamed plus the structured
     * fields (badges / ctas / phishingSuspected / needsReply / replyDrafts).
     */
    record Enriched(String emailId, EnrichedEmail email) implements PipelineEvent {}
}
