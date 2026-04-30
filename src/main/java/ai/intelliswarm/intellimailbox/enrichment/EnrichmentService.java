package ai.intelliswarm.intellimailbox.enrichment;

import ai.intelliswarm.intellimailbox.pipeline.InboxItem;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * One LLM call per email — returns an {@link EnrichmentResult} with summary,
 * badges, structured CTAs, and a phishing flag in one shot.
 *
 * <p>Combines what the Python project did across 3+ separate calls
 * (summary, badge classification, CTA extraction) because doing it as one
 * structured-output call is meaningfully cheaper and faster, especially on
 * local Ollama where each round-trip carries cold-start overhead.
 */
@Service
public class EnrichmentService {

    private static final Logger logger = LoggerFactory.getLogger(EnrichmentService.class);

    private final Agent agent;
    private final long perEmailTimeoutMs;

    public EnrichmentService(ChatClient.Builder chatClientBuilder,
                             @Value("${intellimailbox.preprocessing.per-email-timeout-ms:300000}")
                             long perEmailTimeoutMs) {
        this.perEmailTimeoutMs = perEmailTimeoutMs;
        this.agent = Agent.builder()
                .role("Mailbox Analyst")
                .goal("Read one email and return: a 1-2 sentence summary, the multi-category "
                        + "badges that apply, and a list of structured action items the recipient "
                        + "could take, plus a phishing flag if anything looks suspicious.")
                .backstory("You are a precise executive assistant. You never invent senders, "
                        + "links, dates, or amounts that aren't in the email text. If the email is "
                        + "informational with no action needed, return an empty ctas list.")
                .chatClient(chatClientBuilder.build())
                .verbose(false)
                .temperature(0.1)
                .maxExecutionTime((int) Math.min(perEmailTimeoutMs, Integer.MAX_VALUE))
                .build();
    }

    /**
     * Runs the LLM and produces a typed {@link EnrichedEmail}. Never throws —
     * failures become a {@link EnrichedEmail#failed} record so the SSE stream
     * always gets one event per inbox row.
     */
    public EnrichedEmail enrich(InboxItem item, String emailBody) {
        long t0 = System.currentTimeMillis();
        if (emailBody == null || emailBody.isBlank()) {
            return new EnrichedEmail(item, "(empty email body — nothing to analyze)",
                    List.of(), List.of(), false, 0L, false);
        }
        Task task = Task.builder()
                .description(buildPrompt(emailBody))
                .expectedOutput("EnrichmentResult JSON with summary, badges[], ctas[], phishingSuspected")
                .outputType(EnrichmentResult.class)
                .agent(agent)
                .build();

        try {
            TaskOutput out = agent.executeTask(task, java.util.List.of());
            EnrichmentResult result = out.as(EnrichmentResult.class);
            long elapsed = System.currentTimeMillis() - t0;
            if (result == null) {
                logger.debug("EnrichmentService: LLM returned unparseable output for id={}; raw={}",
                        item.id(), excerpt(out.getRawOutput(), 200));
                return EnrichedEmail.failed(item, "LLM returned unparseable JSON", elapsed);
            }
            return new EnrichedEmail(
                    item,
                    nullToEmpty(result.summary),
                    parseBadges(result.badges),
                    result.ctas == null ? List.of() : result.ctas,
                    Boolean.TRUE.equals(result.phishingSuspected),
                    elapsed,
                    false);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - t0;
            logger.warn("EnrichmentService: enrichment failed for id={} ({})",
                    item.id(), e.getMessage());
            return EnrichedEmail.failed(item,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed);
        }
    }

    private static String buildPrompt(String emailBody) {
        return """
                Analyze the email below and return a single JSON object matching the schema.

                Rules:
                - summary: 1–2 sentences max, factual, no markdown.
                - badges: subset of [MEETING, RISK, EXTERNAL, AUTOMATED, VIP, FOLLOW_UP, NEWSLETTER, FINANCE].
                  Use multiple where they apply. Empty list if none fit.
                  RISK should ONLY be set for phishing / scam / impersonation content.
                - ctas: list of concrete action items the recipient could take.
                  Empty list if email is informational only.
                  Each cta has:
                    text:        ≤200 chars, restate the action in one short sentence.
                    dueDate:     ISO 8601 date "YYYY-MM-DD" if a deadline is mentioned, else null.
                    type:        one of [REPLY, REVIEW, SCHEDULE, PAY, SUBMIT, READ, OTHER].
                    priority:    one of [LOW, MEDIUM, HIGH] based on urgency cues in the email.
                    sourceQuote: short verbatim snippet (≤120 chars) from the email that justifies this CTA.
                - phishingSuspected: true if the email looks like phishing / impersonation / urgent-warning scam.

                Do NOT invent details. Only extract what is literally in the text.

                EMAIL:
                """ + truncate(emailBody, 4000);
    }

    private static List<Badge> parseBadges(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<Badge> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            try {
                out.add(Badge.valueOf(s.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // Unknown label — drop it rather than fail the whole enrichment.
            }
        }
        return out;
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "\n…[truncated]");
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static String excerpt(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
