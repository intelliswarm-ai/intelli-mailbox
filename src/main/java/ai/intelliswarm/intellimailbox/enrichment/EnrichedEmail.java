package ai.intelliswarm.intellimailbox.enrichment;

import ai.intelliswarm.intellimailbox.pipeline.InboxItem;

import java.util.List;

/**
 * The "final answer" the UI renders for one row: the original inbox metadata
 * plus everything we extracted from the body.
 *
 * @param item               the original {@link InboxItem} (sender / subject / time / snippet)
 * @param summary            1–2 sentence LLM summary, never null
 * @param badges             subset of {@link Badge} that apply
 * @param ctas               structured action items (possibly empty)
 * @param phishingSuspected  true if the model flagged it
 * @param needsReply         true if the email warrants a response from the recipient — newsletters/automated/digest emails should be false
 * @param replyDrafts        three pre-drafted replies (formal/friendly/brief) when {@code needsReply} is true, else null
 * @param processedMs        wall-clock ms spent on the enrichment LLM call(s)
 * @param failed             true if enrichment errored — UI shows a warning, summary holds the cause
 */
public record EnrichedEmail(
        InboxItem item,
        String summary,
        List<Badge> badges,
        List<Cta> ctas,
        boolean phishingSuspected,
        boolean needsReply,
        ReplyDrafts replyDrafts,
        long processedMs,
        boolean failed
) {
    public static EnrichedEmail failed(InboxItem item, String reason, long elapsedMs) {
        return new EnrichedEmail(item, "Enrichment failed — " + reason,
                List.of(), List.of(), false, false, null, elapsedMs, true);
    }
}
