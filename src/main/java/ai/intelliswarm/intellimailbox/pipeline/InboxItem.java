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
                stableIdOf(sender, subject, snippet),
                position == null ? "" : position,
                sender == null ? "" : sender,
                subject == null ? "" : subject,
                snippet == null ? "" : snippet,
                time == null ? "" : time);
    }

    /**
     * Derive a stable id from the three fields that survive Gmail re-renders.
     * SHA-1 truncated to 16 hex chars gives ~64 bits of address space — collision
     * probability is negligible for inbox-scale data (low thousands of emails).
     *
     * <p>Why not include {@code time}: Gmail rewrites relative timestamps
     * ({@code "3 hours ago"} → {@code "Today"} → {@code "Apr 30"}) so it isn't
     * stable across days. Snippet is the strongest disambiguator we have at
     * scrape time — two newsletter issues from the same sender with the same
     * subject will have different snippets (different body previews).
     */
    public static String stableIdOf(String sender, String subject, String snippet) {
        String input = norm(sender) + "|" + norm(subject) + "|" + norm(snippet);
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
