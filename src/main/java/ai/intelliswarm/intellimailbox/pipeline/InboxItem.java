package ai.intelliswarm.intellimailbox.pipeline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * One inbox row scraped from Gmail's listing view.
 *
 * @param id        stable, content-derived id — SHA-1 of sender + subject + snippet,
 *                  truncated to 16 hex chars. Survives Gmail re-ordering and app restarts;
 *                  the same email always gets the same id, even across sessions and across
 *                  changes to the inbox range / sort.
 * @param position  Gmail-DOM positional address ("page:idx" — e.g. {@code "1:3"}). Changes
 *                  every time Gmail reorders the listing. Used ONLY for clicking the row
 *                  inside the attached Chrome — never as a cache key.
 * @param sender    display name (or fallback email) of the sender
 * @param subject   subject line
 * @param snippet   Gmail's preview text (first ~80 chars of the body)
 * @param time      timestamp string as Gmail rendered it ("12:34 PM", "Apr 30", …)
 */
public record InboxItem(
        String id,
        String position,
        String sender,
        String subject,
        String snippet,
        String time
) {
    /** Build an InboxItem with a content-hash id from the scrape fields. */
    public static InboxItem from(String position, String sender, String subject,
                                  String snippet, String time) {
        return new InboxItem(
                stableIdOf(sender, subject, snippet, time),
                position == null ? "" : position,
                sender == null ? "" : sender,
                subject == null ? "" : subject,
                snippet == null ? "" : snippet,
                time == null ? "" : time);
    }

    /**
     * Derive a stable id from the four fields that uniquely identify an
     * inbox row. SHA-1 truncated to 16 hex chars gives ~64 bits of address
     * space — collision probability is negligible for inbox-scale data.
     *
     * <p>Why include {@code time}: without it, repeated identical
     * notifications (e.g. a daily {@code AWS Billing — Daily summary}
     * email whose preview is always {@code "Your usage for ..."}) would
     * collide on sender+subject+snippet alone — the second email's
     * enrichment would silently overwrite the first in the cache. The
     * time field disambiguates them because Gmail's listing always shows
     * a different time per message.
     *
     * <p>Trade-off documented: Gmail rewrites relative timestamps
     * ({@code "3 hours ago"} → {@code "Today"} → {@code "Apr 30"}), so a
     * given email's id can change once its rendered time flips into the
     * next bucket. {@code PreprocessingPipeline.refresh()} detects this
     * via {@code evictDriftedEntries} and evicts the orphan; net effect
     * is at most one redundant re-enrichment per email-per-time-bucket-flip.
     * Better than silent overwrite-collisions, which the previous formula
     * had on every repeat notification.
     */
    public static String stableIdOf(String sender, String subject,
                                     String snippet, String time) {
        String input = norm(sender) + "|" + norm(subject)
                     + "|" + norm(snippet) + "|" + norm(time);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i] & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is required by the JDK spec — this branch is unreachable
            // on any conforming runtime, but keep a deterministic fallback so
            // a hypothetical FIPS-restricted JVM doesn't crash the inbox.
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
