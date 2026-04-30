package ai.intelliswarm.intellimailbox.enrichment;

/**
 * Three pre-drafted replies for one email, one per tone. Generated only when
 * the enrichment pass classified {@code needsReply == true}, so newsletters,
 * automated alerts, and digests don't pay LLM cost for drafts they wouldn't use.
 *
 * @param formal   professional, polite, suitable for client / external comms
 * @param friendly warm, first-person, suitable for teammates / familiar contacts
 * @param brief    1–2 sentences, very direct, suitable for "ack" replies
 */
public record ReplyDrafts(
        String formal,
        String friendly,
        String brief
) {}
