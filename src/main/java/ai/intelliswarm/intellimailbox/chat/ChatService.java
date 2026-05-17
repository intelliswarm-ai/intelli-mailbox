package ai.intelliswarm.intellimailbox.chat;

import ai.intelliswarm.intellimailbox.enrichment.EnrichedEmail;
import ai.intelliswarm.intellimailbox.pipeline.PreprocessingPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Chat with your inbox" — wraps {@link ChatClient} with a RAG retrieval step
 * over the {@link SimpleVectorStore} (populated by {@link InboxIndexer}) and
 * a tool surface ({@link InboxTools}) so the agent can search, filter, and
 * inspect emails mid-turn instead of relying only on what was prefetched.
 *
 * <p>Phase 1 scope: single-turn answers, no conversation memory across calls.
 * Each request retrieves top-K matches for the user message, builds a system
 * prompt with that context, attaches the tools, and returns the model's
 * answer plus the ids it referenced. Phase 2 would add a per-conversation
 * memory store; deferred deliberately because (a) Spring AI's chat memory
 * APIs are still in flux, (b) most "chat with inbox" interactions are
 * single-shot ("did Sarah email me about Q3?").
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    private static final int RETRIEVAL_TOP_K = 5;

    /** Find email ids mentioned by the model in its answer. */
    private static final Pattern ID_PATTERN = Pattern.compile("\\b(\\d+:\\d+)\\b");

    private final ChatClient chatClient;
    private final SimpleVectorStore vectorStore;
    private final InboxTools tools;
    private final PreprocessingPipeline pipeline;

    public ChatService(ChatClient.Builder builder,
                       @Qualifier("inboxVectorStore") SimpleVectorStore vectorStore,
                       InboxTools tools,
                       PreprocessingPipeline pipeline) {
        // Build a dedicated ChatClient so EnrichmentService's tweaks (different
        // temperature, prompt format) don't leak in. Defaults are fine here.
        this.chatClient = builder.build();
        this.vectorStore = vectorStore;
        this.tools = tools;
        this.pipeline = pipeline;
    }

    public ChatAnswer ask(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return new ChatAnswer("Ask me anything about your inbox — try \"what needs my reply today?\" or \"summarize this week's finance emails\".",
                    List.of());
        }
        long t0 = System.currentTimeMillis();

        String retrievedContext = retrieve(userMessage);
        String system = buildSystemPrompt(retrievedContext);

        String answer;
        try {
            answer = chatClient.prompt()
                    .system(system)
                    .user(userMessage)
                    .tools(tools)
                    .call()
                    .content();
        } catch (Throwable t) {
            logger.warn("ChatService: LLM call failed for q='{}': {}",
                    excerpt(userMessage, 80), t.toString());
            return new ChatAnswer(diagnose(t), List.of());
        }

        List<CitedEmail> cites = extractCitations(answer);
        long elapsed = System.currentTimeMillis() - t0;
        logger.info("ChatService: answered in {}ms, {} citation(s)", elapsed, cites.size());
        return new ChatAnswer(answer == null ? "" : answer.trim(), cites);
    }

    private String retrieve(String query) {
        try {
            List<Document> hits = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(RETRIEVAL_TOP_K).build());
            if (hits == null || hits.isEmpty()) return "(no indexed emails matched the query)";
            StringBuilder sb = new StringBuilder();
            for (Document d : hits) {
                String id = String.valueOf(d.getMetadata().getOrDefault("id", d.getId()));
                sb.append("--- email id=").append(id).append(" ---\n");
                sb.append("from: ").append(d.getMetadata().getOrDefault("sender", "")).append('\n');
                sb.append("subject: ").append(d.getMetadata().getOrDefault("subject", "")).append('\n');
                if (d.getText() != null) sb.append(truncate(d.getText(), 1200)).append('\n');
                sb.append('\n');
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            logger.debug("ChatService: retrieval failed: {}", t.toString());
            return "(retrieval unavailable — falling back to tool calls only)";
        }
    }

    private static String buildSystemPrompt(String context) {
        return """
                You are the user's email copilot inside Intelli-mailbox.

                Available tools (Spring AI shows you the full signatures separately —
                this is a calibration hint, not the canonical list):
                  - findEmailsFromSender(sender, onlyWithActions)
                        — case-insensitive substring on sender. USE FIRST whenever
                          the user names a person, company, or domain. Covers both
                          "anything to do from X" (set onlyWithActions=true) and
                          "summarize emails from X" (set false, then synthesize).
                  - searchBySubject(keyword)  — substring match on subject line
                                                 (faster + no embeddings needed)
                  - searchInbox(query)        — semantic match (needs the embedding model)
                  - getEmailById(id)          — full enrichment for one email
                  - listByBadge(badge)        — MEETING / RISK / EXTERNAL / AUTOMATED /
                                                 VIP / FOLLOW_UP / NEWSLETTER / FINANCE
                  - listAllActionItems()      — EVERY cached CTA (use by default when
                                                 the user asks about action items)
                  - listEmailsNeedingReply()  — emails the model flagged needsReply=true
                  - listOverdueActions()      — only CTAs with a past-or-today due date
                                                 (most CTAs have NO due date, so this is
                                                 often empty — don't conclude from emptiness
                                                 that there are no action items at all)
                  - listFlaggedPhishing()     — suspected phishing / risky emails
                  - topSenders(limit)         — sender activity overview
                  - inboxOverview()           — counts: indexed / with_action_items /
                                                 needs_reply / suspected_phishing

                Rules:
                  - Always cite emails by their id in your answer using the format id=<value>
                    so the UI can link them. Never invent ids.
                  - If you don't know something, call a tool — don't guess.
                  - Prefer the retrieved context when it already answers the question; only call
                    tools when the context is insufficient. The retrieval may be empty if the
                    embedding model isn't pulled yet — in that case go straight to tools.
                  - For "what do I need to do" / "explain the action items", use
                    listAllActionItems(). Only use listOverdueActions() when the user
                    explicitly mentions deadlines or overdue.
                  - Keep answers short and concrete. Bullet lists when listing several emails.
                  - Never include the email body verbatim if a one-sentence summary works.

                RETRIEVED CONTEXT (top matches for this message):
                %s
                """.formatted(context);
    }

    private List<CitedEmail> extractCitations(String answer) {
        if (answer == null || answer.isEmpty()) return List.of();
        Matcher m = ID_PATTERN.matcher(answer);
        Set<String> seen = new LinkedHashSet<>();
        List<CitedEmail> out = new ArrayList<>();
        while (m.find()) {
            String id = m.group(1);
            if (!seen.add(id)) continue;
            EnrichedEmail e = pipeline.getCached(id).orElse(null);
            if (e == null || e.item() == null) {
                out.add(new CitedEmail(id, "", ""));
            } else {
                out.add(new CitedEmail(id,
                        nullSafe(e.item().sender()),
                        nullSafe(e.item().subject())));
            }
        }
        return out;
    }

    /** Turn a raw exception into a one-line, actionable message the user
     *  can do something with — not a Java class name. */
    private static String diagnose(Throwable t) {
        String msg = t.getMessage() == null ? "" : t.getMessage();
        String simple = t.getClass().getSimpleName();
        // Known Ollama oddity: under tool-call follow-up rounds with sizeable
        // context, Ollama sometimes returns a response with Content-Type
        // application/octet-stream that Spring AI's JSON converter can't
        // parse. The tool-output cap (5K chars) makes this much rarer, but
        // surface a helpful message if it slips through.
        if (msg.contains("application/octet-stream")
                || msg.contains("OllamaApi$ChatResponse")) {
            return "Ollama returned a malformed response — usually means the model "
                 + "got overloaded by a large tool result. Try a more specific question "
                 + "(narrow to one sender, one badge, or fewer rows). If it keeps "
                 + "happening, restart Ollama or try a larger model.";
        }
        if (msg.contains("404") && msg.contains("not found")
                && msg.contains("nomic-embed-text")) {
            return "Chat semantic search isn't available — the embedding model "
                 + "isn't pulled yet. Open the Welcome screen (restart the app) "
                 + "and click \"Download chat search model\".";
        }
        if (msg.toLowerCase().contains("connection refused")
                || msg.toLowerCase().contains("connect timed out")) {
            return "Can't reach the AI engine. Make sure Ollama is running, "
                 + "then try again.";
        }
        if (msg.contains("context_length_exceeded") || msg.contains("context length")) {
            return "Your question + retrieved context exceeded the model's "
                 + "context window. Try a more specific question.";
        }
        return "Sorry — the model couldn't answer that one. ("
             + simple + (msg.isBlank() ? "" : ": " + excerpt(msg, 120)) + ")";
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
    private static String excerpt(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "…");
    }
    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "…");
    }

    public record ChatAnswer(String text, List<CitedEmail> citations) {}
    public record CitedEmail(String id, String sender, String subject) {}
}
