package ai.intelliswarm.intellimailbox.pipeline;

/**
 * One inbox row scraped from Gmail's listing view.
 *
 * @param id       synthetic positional index ("0", "1", …) — the row position in the
 *                 current visible inbox. Stable across one session, NOT a Gmail message id.
 * @param sender   display name (or fallback email) of the sender
 * @param subject  subject line
 * @param snippet  Gmail's preview text (first ~80 chars of the body)
 * @param time     timestamp string as Gmail rendered it ("12:34 PM", "Apr 30", …)
 */
public record InboxItem(
        String id,
        String sender,
        String subject,
        String snippet,
        String time
) {}
