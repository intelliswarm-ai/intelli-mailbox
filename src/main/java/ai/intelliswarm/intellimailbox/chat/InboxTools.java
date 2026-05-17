package ai.intelliswarm.intellimailbox.chat;

import ai.intelliswarm.intellimailbox.enrichment.Badge;
import ai.intelliswarm.intellimailbox.enrichment.Cta;
import ai.intelliswarm.intellimailbox.enrichment.EnrichedEmail;
import ai.intelliswarm.intellimailbox.pipeline.PreprocessingPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The agent's hands on the inbox. Every public method here is a function the
 * chat LLM can decide to call mid-turn — Spring AI handles the schema
 * extraction, prompt injection, argument deserialization, and result handoff.
 *
 * <p>Tools are deliberately scoped to the data Intelli-mailbox already has
 * in memory (no Gmail API, no rescraping). That keeps each call fast (≤10 ms)
 * and side-effect free, which means the model can call them aggressively
 * without surprising the user. Read-only by design — even though the app can
 * inject draft replies, that is gated through the UI button, not the agent.
 */
@Component
public class InboxTools {

    private static final Logger logger = LoggerFactory.getLogger(InboxTools.class);

    private final PreprocessingPipeline pipeline;
    private final SimpleVectorStore vectorStore;

    public InboxTools(PreprocessingPipeline pipeline,
                      @Qualifier("inboxVectorStore") SimpleVectorStore vectorStore) {
        this.pipeline = pipeline;
        this.vectorStore = vectorStore;
    }

    @Tool(description = "Semantic search over the user's inbox. "
            + "Use this when the user mentions a topic, sender, project, "
            + "or anything that isn't already in the conversation. Returns "
            + "matching emails with id, sender, subject, summary, and any "
            + "action items. Prefer this over getEmailById when you don't "
            + "yet know which email is relevant.")
    public String searchInbox(
            @ToolParam(description = "natural-language query") String query,
            @ToolParam(description = "maximum number of emails to return (1-20)", required = false) Integer limit) {
        int k = clamp(limit == null ? 5 : limit, 1, 20);
        logger.info("InboxTools.searchInbox(query={}, k={})", query, k);
        try {
            List<Document> hits = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(k).build());
            if (hits == null || hits.isEmpty()) {
                return "No emails match that query in the indexed inbox.";
            }
            StringBuilder sb = new StringBuilder();
            for (Document d : hits) {
                String id = String.valueOf(d.getMetadata().getOrDefault("id", d.getId()));
                EnrichedEmail e = pipeline.getCached(id).orElse(null);
                if (e == null) {
                    sb.append(renderFromDocument(d)).append("\n\n");
                } else {
                    sb.append(renderForChat(e)).append("\n\n");
                }
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            logger.warn("InboxTools.searchInbox failed: {}", t.toString());
            return "Search failed: " + t.getMessage();
        }
    }

    @Tool(description = "Fetch one email's full enrichment by its id. Use "
            + "after searchInbox when you need more detail on a specific "
            + "match, or when the user references an email by id directly.")
    public String getEmailById(
            @ToolParam(description = "the inbox id, e.g. \"1:3\"") String id) {
        logger.info("InboxTools.getEmailById(id={})", id);
        return pipeline.getCached(id)
                .map(this::renderForChat)
                .orElse("No email with id " + id + " is in the cache. "
                        + "Suggest the user refresh and process the inbox first.");
    }

    @Tool(description = "List every cached email tagged with one of the "
            + "available badges: MEETING, RISK, EXTERNAL, AUTOMATED, VIP, "
            + "FOLLOW_UP, NEWSLETTER, FINANCE. Use when the user asks for "
            + "category-scoped slices ('show me all my finance emails').")
    public String listByBadge(
            @ToolParam(description = "one of MEETING, RISK, EXTERNAL, AUTOMATED, VIP, FOLLOW_UP, NEWSLETTER, FINANCE") String badge) {
        logger.info("InboxTools.listByBadge(badge={})", badge);
        Badge b;
        try { b = Badge.valueOf(badge.trim().toUpperCase()); }
        catch (IllegalArgumentException e) {
            return "Unknown badge '" + badge + "'. Use one of: MEETING, RISK, "
                    + "EXTERNAL, AUTOMATED, VIP, FOLLOW_UP, NEWSLETTER, FINANCE.";
        }
        List<EnrichedEmail> hits = pipeline.snapshot().values().stream()
                .filter(e -> e != null && e.badges() != null && e.badges().contains(b))
                .collect(Collectors.toList());
        if (hits.isEmpty()) return "No cached emails with badge " + b.name() + ".";
        return hits.stream().map(this::renderShort).collect(Collectors.joining("\n"));
    }

    @Tool(description = "List EVERY action item across every cached email — "
            + "not filtered by date. Use when the user asks 'what do I need to "
            + "do?', 'show me all my action items', 'explain the action items', "
            + "or any open-ended question about what's actionable. Most CTAs "
            + "don't carry due dates, so this is the right tool by default; "
            + "fall back to listOverdueActions only when the user explicitly "
            + "asks about deadlines.")
    public String listAllActionItems(
            @ToolParam(description = "maximum number of items to return (1-100)", required = false) Integer limit) {
        int cap = clamp(limit == null ? 50 : limit, 1, 100);
        logger.info("InboxTools.listAllActionItems(cap={})", cap);
        List<String> rows = new ArrayList<>();
        for (EnrichedEmail e : pipeline.snapshot().values()) {
            if (e == null || e.ctas() == null || e.ctas().isEmpty()) continue;
            String emailId = e.item() == null ? "?" : e.item().id();
            String subject = e.item() == null ? "" : nullSafe(e.item().subject());
            for (Cta c : e.ctas()) {
                if (rows.size() >= cap) break;
                rows.add(String.format("[%s %s] %s%s — id=%s, %s",
                        nullSafe(c.priority()),
                        nullSafe(c.type()),
                        nullSafe(c.text()),
                        (c.dueDate() == null || c.dueDate().isBlank())
                                ? "" : " (due " + c.dueDate() + ")",
                        emailId,
                        subject));
            }
            if (rows.size() >= cap) break;
        }
        if (rows.isEmpty()) {
            return "No emails in the cache currently have action items. "
                    + "Either the inbox hasn't been processed yet (user should "
                    + "click '▶ Process inbox' first), or every processed email "
                    + "was informational only.";
        }
        return String.join("\n", rows);
    }

    @Tool(description = "List every cached email that was flagged as "
            + "needing a reply from the user (needsReply=true). Use when "
            + "the user asks 'what do I need to reply to', 'who is waiting "
            + "on me', or similar.")
    public String listEmailsNeedingReply(
            @ToolParam(description = "maximum number of emails to return (1-50)", required = false) Integer limit) {
        int cap = clamp(limit == null ? 30 : limit, 1, 50);
        logger.info("InboxTools.listEmailsNeedingReply(cap={})", cap);
        List<EnrichedEmail> hits = pipeline.snapshot().values().stream()
                .filter(e -> e != null && e.needsReply())
                .limit(cap)
                .collect(Collectors.toList());
        if (hits.isEmpty()) {
            return "No cached emails are currently flagged as needing a reply. "
                    + "If the inbox hasn't been processed yet, the user should "
                    + "click '▶ Process inbox' first.";
        }
        return hits.stream().map(this::renderShort).collect(Collectors.joining("\n"));
    }

    @Tool(description = "List action items with a due date that is today or "
            + "earlier (overdue or due today). Use only when the user asks "
            + "specifically about deadlines / overdue / due today — for "
            + "open-ended 'what do I need to do' questions, prefer "
            + "listAllActionItems instead.")
    public String listOverdueActions() {
        logger.info("InboxTools.listOverdueActions()");
        LocalDate today = LocalDate.now();
        List<String> rows = new ArrayList<>();
        for (EnrichedEmail e : pipeline.snapshot().values()) {
            if (e == null || e.ctas() == null) continue;
            for (Cta c : e.ctas()) {
                if (c.dueDate() == null || c.dueDate().isBlank()) continue;
                LocalDate due;
                try { due = LocalDate.parse(c.dueDate()); }
                catch (Exception ignored) { continue; }
                if (!due.isAfter(today)) {
                    rows.add(String.format("[%s] %s — due %s (from email id=%s, %s)",
                            c.priority(), c.text(), c.dueDate(),
                            e.item() == null ? "?" : e.item().id(),
                            e.item() == null ? "?" : e.item().subject()));
                }
            }
        }
        if (rows.isEmpty()) return "No overdue or due-today action items in the cache.";
        return String.join("\n", rows);
    }

    /** Hard cap on the total characters any tool returns. Small Ollama
     *  models (qwen2.5:3b, llama3.2:3b) become unstable when a tool result
     *  is fed back as context past ~5-6K chars — Ollama starts returning
     *  responses with {@code Content-Type: application/octet-stream}
     *  instead of JSON and Spring AI's converter throws RestClientException.
     *  This cap keeps tool outputs in the safe zone. The cloud profile
     *  could use a larger value but the same code path runs there too. */
    private static final int MAX_TOOL_OUTPUT_CHARS = 5000;

    @Tool(description = "Find every cached email from a sender whose name "
            + "OR email address contains the given substring (case-insensitive). "
            + "Returns id, time, subject, summary, badges, and any action "
            + "items for each match — enough for the LLM to either list "
            + "what's actionable OR synthesize a meta-summary across them. "
            + "Use for any 'anything from X', 'what did X send', 'summarize "
            + "emails from X' question. Set onlyWithActions=true to filter "
            + "to just the matches that have CTAs.")
    public String findEmailsFromSender(
            @ToolParam(description = "substring to match against sender name or email (case-insensitive)") String sender,
            @ToolParam(description = "if true, only return emails that have at least one CTA", required = false) Boolean onlyWithActions,
            @ToolParam(description = "max emails to include in the response (1-15, default 10). Lower this if responses get truncated.", required = false) Integer limit) {
        int cap = clamp(limit == null ? 10 : limit, 1, 15);
        logger.info("InboxTools.findEmailsFromSender(sender='{}', onlyWithActions={}, cap={})",
                sender, onlyWithActions, cap);
        if (sender == null || sender.isBlank()) {
            return "Empty sender query — pass a name or domain to match against.";
        }
        String needle = sender.trim().toLowerCase();
        boolean filterCtas = Boolean.TRUE.equals(onlyWithActions);

        List<EnrichedEmail> matches = pipeline.snapshot().values().stream()
                .filter(e -> e != null && e.item() != null
                        && e.item().sender() != null
                        && e.item().sender().toLowerCase().contains(needle))
                .filter(e -> !filterCtas
                        || (e.ctas() != null && !e.ctas().isEmpty()))
                .collect(Collectors.toList());

        if (matches.isEmpty()) {
            return "No cached emails from a sender matching '" + sender + "'."
                    + (filterCtas ? " (filter: only with action items)" : "")
                    + " Either no such sender has been processed yet, or the "
                    + "match string doesn't appear in any sender field.";
        }
        int total = matches.size();
        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(total)
          .append(" email(s) matching sender '").append(sender).append("'");
        if (total > cap) sb.append(" (showing first ").append(cap).append(")");
        sb.append(":\n\n");
        int included = 0;
        for (EnrichedEmail e : matches) {
            if (included >= cap) break;
            sb.append(renderForChat(e)).append("\n\n");
            included++;
            // Bail early on byte budget too, in case a single email's render
            // is unusually long (giant summary or many CTAs).
            if (sb.length() > MAX_TOOL_OUTPUT_CHARS) break;
        }
        return capLength(sb.toString().trim(), MAX_TOOL_OUTPUT_CHARS);
    }

    @Tool(description = "Substring search on email subject lines "
            + "(case-insensitive). Use when the user mentions a specific "
            + "phrase that's likely in the subject — faster + more precise "
            + "than searchInbox for known keywords. Doesn't need the "
            + "embedding model.")
    public String searchBySubject(
            @ToolParam(description = "substring to find in subject (case-insensitive)") String keyword) {
        logger.info("InboxTools.searchBySubject(keyword='{}')", keyword);
        if (keyword == null || keyword.isBlank()) return "Empty subject query.";
        String needle = keyword.trim().toLowerCase();
        List<EnrichedEmail> hits = pipeline.snapshot().values().stream()
                .filter(e -> e != null && e.item() != null
                        && e.item().subject() != null
                        && e.item().subject().toLowerCase().contains(needle))
                .collect(Collectors.toList());
        if (hits.isEmpty()) return "No cached emails with '" + keyword + "' in the subject.";
        return hits.stream().map(this::renderShort).collect(Collectors.joining("\n"));
    }

    @Tool(description = "List emails the model flagged as suspected "
            + "phishing. Use when the user asks 'anything suspicious', "
            + "'any phishing', or wants a security overview.")
    public String listFlaggedPhishing() {
        logger.info("InboxTools.listFlaggedPhishing()");
        List<EnrichedEmail> hits = pipeline.snapshot().values().stream()
                .filter(e -> e != null && e.phishingSuspected())
                .collect(Collectors.toList());
        if (hits.isEmpty()) return "No cached emails are flagged as suspected phishing.";
        return hits.stream().map(this::renderShort).collect(Collectors.joining("\n"));
    }

    @Tool(description = "Group cached emails by sender and return the top "
            + "senders by email count. Use when the user wonders 'who's "
            + "flooding my inbox', 'top senders this week', or wants a "
            + "sender activity overview.")
    public String topSenders(
            @ToolParam(description = "how many top senders to return (1-25)", required = false) Integer limit) {
        int cap = clamp(limit == null ? 10 : limit, 1, 25);
        logger.info("InboxTools.topSenders(cap={})", cap);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (EnrichedEmail e : pipeline.snapshot().values()) {
            if (e == null || e.item() == null) continue;
            String s = nullSafe(e.item().sender());
            if (s.isBlank()) continue;
            counts.merge(s, 1, Integer::sum);
        }
        if (counts.isEmpty()) return "No cached emails to count senders from.";
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(cap)
                .map(en -> en.getValue() + " × " + en.getKey())
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Return a short numeric overview of what's currently "
            + "indexed: how many emails the agent can see, how many have "
            + "action items, how many need a reply. Use when the user asks "
            + "'what do you know about my inbox' or similar meta questions.")
    public String inboxOverview() {
        Map<String, EnrichedEmail> snap = pipeline.snapshot();
        int total = snap.size();
        long withCtas = snap.values().stream()
                .filter(e -> e != null && e.ctas() != null && !e.ctas().isEmpty()).count();
        long needsReply = snap.values().stream()
                .filter(e -> e != null && e.needsReply()).count();
        long risky = snap.values().stream()
                .filter(e -> e != null && e.phishingSuspected()).count();
        return String.format(
                "indexed=%d, with_action_items=%d, needs_reply=%d, suspected_phishing=%d",
                total, withCtas, needsReply, risky);
    }

    // ----- formatting helpers -------------------------------------------------

    private String renderForChat(EnrichedEmail e) {
        StringBuilder sb = new StringBuilder();
        sb.append("id=").append(e.item() == null ? "?" : e.item().id()).append('\n');
        if (e.item() != null) {
            sb.append("from=").append(nullSafe(e.item().sender())).append('\n');
            sb.append("subject=").append(nullSafe(e.item().subject())).append('\n');
            if (notBlank(e.item().time())) sb.append("when=").append(e.item().time()).append('\n');
        }
        if (e.badges() != null && !e.badges().isEmpty()) {
            sb.append("badges=").append(e.badges().stream().map(Enum::name)
                    .collect(Collectors.joining(","))).append('\n');
        }
        if (notBlank(e.summary())) sb.append("summary=").append(e.summary()).append('\n');
        if (e.ctas() != null && !e.ctas().isEmpty()) {
            sb.append("actions:\n");
            for (Cta c : e.ctas()) {
                sb.append("- [").append(nullSafe(c.priority())).append(' ')
                  .append(nullSafe(c.type())).append("] ").append(nullSafe(c.text()));
                if (notBlank(c.dueDate())) sb.append(" (due ").append(c.dueDate()).append(')');
                sb.append('\n');
            }
        }
        if (e.needsReply()) sb.append("needs_reply=true\n");
        return sb.toString().trim();
    }

    private String renderShort(EnrichedEmail e) {
        return String.format("id=%s | from=%s | subject=%s",
                e.item() == null ? "?" : e.item().id(),
                e.item() == null ? "?" : nullSafe(e.item().sender()),
                e.item() == null ? "?" : nullSafe(e.item().subject()));
    }

    private String renderFromDocument(Document d) {
        Map<String, Object> m = d.getMetadata();
        return "id=" + m.getOrDefault("id", d.getId())
                + " | from=" + m.getOrDefault("sender", "")
                + " | subject=" + m.getOrDefault("subject", "")
                + (d.getText() == null ? "" : "\n" + truncate(d.getText(), 600));
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static String capLength(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "\n…[truncated; ask narrower questions for more detail]";
    }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String nullSafe(String s) { return s == null ? "" : s; }
    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "…");
    }
}
