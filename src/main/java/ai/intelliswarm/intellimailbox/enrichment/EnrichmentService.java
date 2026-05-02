package ai.intelliswarm.intellimailbox.enrichment;

import ai.intelliswarm.intellimailbox.pipeline.InboxItem;
import ai.intelliswarm.intellimailbox.pipeline.PipelineEvent;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.streaming.AgentEvent;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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

    private final Agent enrichmentAgent;
    private final Agent draftAgent;

    /**
     * Output-language preference. {@code "auto"} = match the email's own
     * language (default). Read fresh from the system property on every prompt
     * build so flipping the Settings dropdown takes effect on the next email
     * — no app restart required.
     */
    private static String outputLanguage() {
        String v = System.getProperty("intellimailbox.language");
        if (v == null || v.isBlank()) v = System.getenv("INTELLIMAILBOX_LANGUAGE");
        return (v == null || v.isBlank()) ? "auto" : v.trim().toLowerCase();
    }

    /** Translate the saved ISO code into a directive the LLM understands. */
    private static String languageDirective() {
        String code = outputLanguage();
        if ("auto".equals(code)) {
            return "Match the LANGUAGE of the email body (e.g. Greek email → Greek, French → French).";
        }
        String name = switch (code) {
            case "en" -> "English";
            case "el" -> "Greek (Ελληνικά)";
            case "es" -> "Spanish";
            case "fr" -> "French";
            case "de" -> "German";
            case "it" -> "Italian";
            case "pt" -> "Portuguese";
            case "nl" -> "Dutch";
            case "pl" -> "Polish";
            case "sv" -> "Swedish";
            case "tr" -> "Turkish";
            case "ru" -> "Russian";
            case "uk" -> "Ukrainian";
            case "ar" -> "Arabic";
            case "he" -> "Hebrew";
            case "hi" -> "Hindi";
            case "ja" -> "Japanese";
            case "ko" -> "Korean";
            case "zh" -> "Chinese";
            default   -> code; // unknown ISO code — pass through to the LLM
        };
        return "Write all generated text (summary, cta.text, reply drafts) in " + name
                + ", regardless of the email's original language. "
                + "EXCEPTION: cta.sourceQuote MUST be a verbatim excerpt from the email — do not translate it.";
    }

    public EnrichmentService(ChatClient.Builder chatClientBuilder,
                             @Value("${intellimailbox.preprocessing.per-email-timeout-ms:300000}")
                             long perEmailTimeoutMs) {
        int timeout = (int) Math.min(perEmailTimeoutMs, Integer.MAX_VALUE);
        ChatClient chatClient = chatClientBuilder.build();

        this.enrichmentAgent = Agent.builder()
                .role("Mailbox Analyst")
                .goal("Read one email and return: a 1-2 sentence summary, the multi-category "
                        + "badges that apply, structured action items the recipient could take, "
                        + "a phishing flag, and whether a reply is genuinely warranted.")
                .backstory("You are a precise executive assistant. You never invent senders, "
                        + "links, dates, or amounts that aren't in the email text. If the email is "
                        + "informational with no action needed, return an empty ctas list and "
                        + "needsReply=false.")
                .chatClient(chatClient)
                .verbose(false)
                .temperature(0.1)
                .maxExecutionTime(timeout)
                .build();

        // Higher temp on the drafter so the three tones actually diverge. With qwen2.5:3b
        // at 0.5 it produced stock acknowledgments ("I have noted your request and will
        // proceed as instructed"); 0.75 gives it room to address the actual ask.
        this.draftAgent = Agent.builder()
                .role("Reply Drafter")
                .goal("Given one email, produce three short reply drafts in different tones: "
                        + "formal, friendly, and brief.")
                .backstory("You write concise, professional email replies that address the actual "
                        + "question or ask in the email. You never agree to things the original "
                        + "email didn't ask, never invent dates or commitments, and never break "
                        + "character (no AI-disclosure prefixes, no meta commentary, no stock "
                        + "acknowledgements like 'I have noted your request').")
                .chatClient(chatClient)
                .verbose(false)
                .temperature(0.75)
                .maxExecutionTime(timeout)
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
                    List.of(), List.of(), false, false, null, 0L, false);
        }
        Task task = Task.builder()
                .description(buildPrompt(item, emailBody))
                .expectedOutput("EnrichmentResult JSON with summary, badges[], ctas[], phishingSuspected, needsReply")
                .outputType(EnrichmentResult.class)
                .agent(enrichmentAgent)
                .build();

        EnrichmentResult result;
        try {
            TaskOutput out = enrichmentAgent.executeTask(task, java.util.List.of());
            result = out.as(EnrichmentResult.class);
            if (result == null) {
                long elapsed = System.currentTimeMillis() - t0;
                logger.debug("EnrichmentService: LLM returned unparseable output for id={}; raw={}",
                        item.id(), excerpt(out.getRawOutput(), 200));
                return EnrichedEmail.failed(item, "LLM returned unparseable JSON", elapsed);
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - t0;
            logger.warn("EnrichmentService: enrichment failed for id={} ({})",
                    item.id(), e.getMessage());
            return EnrichedEmail.failed(item,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed);
        }

        List<Badge> badges = parseBadges(result.badges);
        List<Cta> ctas = filterTruthful(normalizeCtas(result.ctas), emailBody);

        // Hardcoded guards on top of the model's own classification — qwen2.5:3b
        // overcalls needsReply=true on marketing/newsletter mail. If the email is
        // automated or a newsletter, no human reply is warranted regardless of what
        // the model said. Same logic as the badge calibration block in the prompt
        // — applying it deterministically server-side rather than trusting a 3B model.
        boolean needsReply = Boolean.TRUE.equals(result.needsReply)
                && !badges.contains(Badge.AUTOMATED)
                && !badges.contains(Badge.NEWSLETTER);
        // Also: if there are zero remaining CTAs OR none of them are REPLY-typed,
        // a draft would be answering nothing. Skip.
        if (needsReply && ctas.stream().noneMatch(c -> "REPLY".equals(c.type())
                || "OTHER".equals(c.type())
                || "REVIEW".equals(c.type())
                || "SCHEDULE".equals(c.type()))) {
            needsReply = false;
        }

        ReplyDrafts drafts = needsReply
                ? draftReplies(item, emailBody, nullToEmpty(result.summary))
                : null;

        long elapsed = System.currentTimeMillis() - t0;
        return new EnrichedEmail(
                item,
                stripLLMArtifacts(nullToEmpty(result.summary)),
                badges,
                scrubCtas(ctas),
                Boolean.TRUE.equals(result.phishingSuspected),
                needsReply,
                scrubDrafts(drafts),
                elapsed,
                false);
    }

    // -----------------------------------------------------------------------
    // Streaming variant — same pipeline, but the per-email summary is delivered
    // token-by-token while the structured/draft passes run in the background.
    // -----------------------------------------------------------------------

    /**
     * Streaming variant of {@link #enrich(InboxItem, String)}. Returns a
     * {@link Flux} of {@link PipelineEvent}s:
     *
     * <ol>
     *   <li>Many {@link PipelineEvent.SummaryDelta} as the LLM streams the prose summary.</li>
     *   <li>Exactly one terminal {@link PipelineEvent.Enriched} with the fully-built
     *       {@link EnrichedEmail} (summary + badges + ctas + drafts).</li>
     * </ol>
     *
     * <p>Total LLM work is comparable to the non-streaming path: a streaming summary
     * pass replaces the summary slot in what was the combined first call, then the
     * structured-fields pass uses the already-known summary as context (so the LLM
     * doesn't waste tokens regenerating it). Drafts pass behaves identically.
     *
     * <p>Errors are NEVER signalled via {@code onError} — they materialise as a
     * terminal {@link PipelineEvent.Enriched} carrying an {@link EnrichedEmail#failed}.
     * The Flux always completes normally so SSE consumers can keep their subscribe
     * lambda small (no error handler).
     *
     * @since 1.1.0
     */
    public Flux<PipelineEvent> enrichStreaming(InboxItem item, String emailBody) {
        long t0 = System.currentTimeMillis();
        if (emailBody == null || emailBody.isBlank()) {
            EnrichedEmail empty = new EnrichedEmail(item,
                    "(empty email body — nothing to analyze)",
                    List.of(), List.of(), false, false, null, 0L, false);
            return Flux.just(new PipelineEvent.Enriched(item.id(), empty));
        }

        // ---- pass 1: stream the prose summary ---------------------------------
        StringBuilder summaryAccum = new StringBuilder();
        Task summaryTask = Task.builder()
                .description(buildStreamingSummaryPrompt(item, emailBody))
                .expectedOutput("A 1–2 sentence prose summary, no JSON, no markdown")
                .agent(enrichmentAgent)
                .build();

        Flux<PipelineEvent> deltas = enrichmentAgent
                .executeTaskStreaming(summaryTask, java.util.List.of())
                .filter(evt -> evt instanceof AgentEvent.TextDelta)
                .map(evt -> ((AgentEvent.TextDelta) evt).text())
                .doOnNext(summaryAccum::append)
                .map(text -> (PipelineEvent) new PipelineEvent.SummaryDelta(item.id(), text))
                .onErrorResume(err -> {
                    logger.warn("EnrichmentService: summary stream failed for id={}: {}",
                            item.id(), err.getMessage());
                    return Flux.empty();
                });

        // ---- passes 2 + 3: structured fields, then drafts (if needsReply) ----
        // Mono is invariant in its type param, so we widen via .<PipelineEvent>fromSupplier.
        Mono<PipelineEvent> finalize = Mono.<PipelineEvent>fromSupplier(() -> {
            String summary = summaryAccum.toString();
            EnrichedEmail finalEmail = runStructuredAndDrafts(item, emailBody, summary, t0);
            return new PipelineEvent.Enriched(item.id(), finalEmail);
        }).onErrorResume(err -> {
            long elapsed = System.currentTimeMillis() - t0;
            logger.warn("EnrichmentService: structured pass failed for id={}: {}",
                    item.id(), err.getMessage());
            return Mono.just(new PipelineEvent.Enriched(item.id(),
                    EnrichedEmail.failed(item, err.getClass().getSimpleName(), elapsed)));
        });

        return Flux.concat(deltas, finalize);
    }

    /**
     * Runs the structured-fields LLM call and (if needed) the drafts pass.
     * Mirrors {@link #enrich(InboxItem, String)}'s second-half logic exactly,
     * but uses the {@code knownSummary} that the streaming pass produced so the
     * LLM doesn't waste tokens regenerating prose that's already on the wire.
     */
    private EnrichedEmail runStructuredAndDrafts(InboxItem item, String emailBody,
                                                  String knownSummary, long t0) {
        Task task = Task.builder()
                .description(buildStructuredOnlyPrompt(item, emailBody, knownSummary))
                .expectedOutput("EnrichmentResult JSON with badges[], ctas[], phishingSuspected, needsReply (summary already known)")
                .outputType(EnrichmentResult.class)
                .agent(enrichmentAgent)
                .build();

        EnrichmentResult result;
        try {
            TaskOutput out = enrichmentAgent.executeTask(task, java.util.List.of());
            result = out.as(EnrichmentResult.class);
            if (result == null) {
                long elapsed = System.currentTimeMillis() - t0;
                return EnrichedEmail.failed(item, "structured pass: unparseable JSON", elapsed);
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - t0;
            return EnrichedEmail.failed(item,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed);
        }

        List<Badge> badges = parseBadges(result.badges);
        List<Cta> ctas = filterTruthful(normalizeCtas(result.ctas), emailBody);

        boolean needsReply = Boolean.TRUE.equals(result.needsReply)
                && !badges.contains(Badge.AUTOMATED)
                && !badges.contains(Badge.NEWSLETTER);
        if (needsReply && ctas.stream().noneMatch(c -> "REPLY".equals(c.type())
                || "OTHER".equals(c.type())
                || "REVIEW".equals(c.type())
                || "SCHEDULE".equals(c.type()))) {
            needsReply = false;
        }

        ReplyDrafts drafts = needsReply
                ? draftReplies(item, emailBody, knownSummary)
                : null;

        long elapsed = System.currentTimeMillis() - t0;
        return new EnrichedEmail(
                item,
                stripLLMArtifacts(knownSummary),
                badges,
                scrubCtas(ctas),
                Boolean.TRUE.equals(result.phishingSuspected),
                needsReply,
                scrubDrafts(drafts),
                elapsed,
                false);
    }

    /** Streaming-summary prompt — plain prose only, no JSON, no preamble. */
    private static String buildStreamingSummaryPrompt(InboxItem item, String emailBody) {
        return """
                Read this email and write a single 1–2 sentence summary in plain prose.
                No JSON, no markdown, no preamble — just the summary text.
                Don't invent senders, dates, links or amounts that aren't in the body.

                LANGUAGE: %s

                EMAIL METADATA:
                  From:    %s
                  Subject: %s
                  Time:    %s

                EMAIL BODY:
                %s
                """.formatted(
                        languageDirective(),
                        nullToDash(item.sender()),
                        nullToDash(item.subject()),
                        nullToDash(item.time()),
                        truncate(emailBody, 4000));
    }

    /** Structured-fields prompt — same as {@link #buildPrompt} but tells the LLM the summary is already known. */
    private static String buildStructuredOnlyPrompt(InboxItem item, String emailBody, String knownSummary) {
        return """
                You are an executive assistant. Extract structured fields for ONE email.
                The summary is ALREADY KNOWN (provided below for context only — do not regenerate it).
                Return a JSON object with badges, ctas, phishingSuspected, and needsReply.
                Leave the summary field empty.

                LANGUAGE: %s

                Output rules:
                - badges: subset of [MEETING, RISK, EXTERNAL, AUTOMATED, VIP, FOLLOW_UP, NEWSLETTER, FINANCE].
                  Calibration:
                    MEETING     → calendar invites, scheduling discussion, accept/decline.
                    RISK        → phishing, scam, impersonation, urgent-credentials warning.
                    EXTERNAL    → from outside your org / vendor / recruiter / personal contact.
                    AUTOMATED   → no-reply, system, build/CI/Dependabot/security-scan, transactional.
                    VIP         → from or about a senior exec, key customer, high-priority contact.
                    FOLLOW_UP   → expects a reply, ping, RSVP, application.
                    NEWSLETTER  → marketing/digest/promo. NOT job alerts, NOT calendar invites,
                                  NOT GitHub alerts — those are EXTERNAL or AUTOMATED.
                    FINANCE     → invoice, receipt, banking, market report, payment.
                - ctas: list of concrete action items. Empty list if informational only.
                  Each cta: text (≤200 chars), dueDate (ISO 8601 or null), type (one of
                  [REPLY, REVIEW, SCHEDULE, PAY, SUBMIT, READ, OTHER]), priority
                  (one of [LOW, MEDIUM, HIGH]), sourceQuote (≤120-char verbatim excerpt).
                - phishingSuspected: true ONLY for genuine phishing/impersonation/urgent-credentials scam.
                - needsReply: true ONLY if a human response is genuinely warranted
                  (direct question, ask for review/decision, calendar invite, recruiter outreach).
                  false for newsletters, marketing, automated alerts, no-reply transactional, FYI broadcasts.

                EMAIL METADATA:
                  From:    %s
                  Subject: %s
                  Time:    %s
                  Snippet: %s

                ALREADY-KNOWN SUMMARY (context only — leave the summary field empty in your response):
                %s

                EMAIL BODY:
                %s
                """.formatted(
                        languageDirective(),
                        nullToDash(item.sender()),
                        nullToDash(item.subject()),
                        nullToDash(item.time()),
                        nullToDash(item.snippet()),
                        nullToDash(knownSummary),
                        truncate(emailBody, 4000));
    }

    /**
     * Drop CTAs whose {@code sourceQuote} doesn't actually appear in the email body —
     * those are model hallucinations, frequently from Gmail UI chrome leaking into
     * the scrape (e.g. "You can't react with an emoji to a group"). Lossy but high-precision.
     */
    private static List<Cta> filterTruthful(List<Cta> ctas, String body) {
        if (ctas == null || ctas.isEmpty()) return List.of();
        if (body == null || body.isBlank()) return ctas;
        String haystack = body.toLowerCase();
        List<Cta> out = new ArrayList<>(ctas.size());
        for (Cta c : ctas) {
            String quote = c.sourceQuote();
            if (quote == null || quote.isBlank()) {
                // No quote → keep the CTA but don't claim provenance.
                out.add(c);
                continue;
            }
            // Match a substring of the quote (first ~6 words) rather than the whole
            // thing — Gmail-UI chrome quotes won't, real body excerpts will.
            String needle = collapseWhitespace(quote).toLowerCase();
            if (needle.length() > 60) needle = needle.substring(0, 60);
            if (haystack.contains(needle) || haystack.contains(collapseWhitespace(quote.toLowerCase()))) {
                out.add(c);
            }
            // else dropped silently — the model invented the quote.
        }
        return out;
    }

    private static String collapseWhitespace(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /**
     * Pass 2 — only runs for emails the first pass classified {@code needsReply=true}.
     * Returns three short drafts, one per tone. Failures here don't fail the whole
     * enrichment — we just return null and the UI omits the drafts section.
     */
    private ReplyDrafts draftReplies(InboxItem item, String emailBody, String summary) {
        Task task = Task.builder()
                .description(buildDraftPrompt(item, emailBody, summary))
                .expectedOutput("JSON with three string fields: formal, friendly, brief")
                .outputType(ReplyDraftsLlmOutput.class)
                .agent(draftAgent)
                .build();
        try {
            TaskOutput out = draftAgent.executeTask(task, java.util.List.of());
            ReplyDraftsLlmOutput d = out.as(ReplyDraftsLlmOutput.class);
            if (d == null) {
                logger.debug("EnrichmentService: drafter returned unparseable output for id={}", item.id());
                return null;
            }
            return new ReplyDrafts(
                    nullToEmpty(d.formal).trim(),
                    nullToEmpty(d.friendly).trim(),
                    nullToEmpty(d.brief).trim());
        } catch (Exception e) {
            logger.debug("EnrichmentService: draft pass failed for id={} ({}) — UI will omit drafts",
                    item.id(), e.getMessage());
            return null;
        }
    }

    /**
     * Anchors the prompt with the inbox-row metadata. With small models the body
     * scrape is noisy (Gmail UI chrome, sidebar text); without an explicit From/
     * Subject anchor the LLM tends to hallucinate the wrong topic.
     */
    private static String buildPrompt(InboxItem item, String emailBody) {
        return """
                You are an executive assistant. Analyze ONE email and return a single JSON object matching the schema.

                LANGUAGE: %s

                Output rules:
                - summary: 1–2 sentences max, factual, no markdown. Must be about the email below — never invent senders, links, dates, or amounts that aren't in the text.
                - badges: subset of [MEETING, RISK, EXTERNAL, AUTOMATED, VIP, FOLLOW_UP, NEWSLETTER, FINANCE].
                  Pick zero or more, only the ones that genuinely apply. Calibration:
                    MEETING     → calendar invites, scheduling discussion, accept/decline.
                    RISK        → phishing, scam, impersonation, urgent-credentials warning. Almost never else.
                    EXTERNAL    → from outside your org / a vendor / a recruiter / a personal contact.
                    AUTOMATED   → no-reply, system, build/CI/Dependabot/security-scan, transactional.
                    VIP         → from or about a senior exec, key customer, or high-priority contact.
                    FOLLOW_UP   → expects a reply, ping, response, RSVP, application.
                    NEWSLETTER  → marketing/digest/promo content. NOT job alerts, NOT calendar invites,
                                  NOT GitHub alerts — those are EXTERNAL or AUTOMATED.
                    FINANCE     → invoice, receipt, banking, market report, payment.
                  Use multiple where they apply (a recruiter alert is EXTERNAL + FOLLOW_UP).
                - ctas: list of concrete action items the recipient could take. Empty list if informational only.
                  Each cta has:
                    text:        ≤200 chars, restate the action in one short sentence.
                    dueDate:     ISO 8601 date "YYYY-MM-DD" if a deadline is mentioned, else null.
                    type:        EXACTLY one of [REPLY, REVIEW, SCHEDULE, PAY, SUBMIT, READ, OTHER]. No other values.
                    priority:    EXACTLY one of [LOW, MEDIUM, HIGH] based on urgency cues. No other values.
                    sourceQuote: short verbatim snippet (≤120 chars) from the email body that justifies this CTA.
                - phishingSuspected: true ONLY if the email genuinely looks like phishing / impersonation / urgent-credentials scam.
                - needsReply: true ONLY if a human response is genuinely warranted. Calibration:
                    true   → direct question to recipient, ask for review/decision/info, calendar invite needing accept/decline,
                             recruiter outreach you might engage with, personal message expecting a response.
                    false  → newsletters, marketing, digests, GitHub Dependabot/CI/security alerts, no-reply transactional mail,
                             FYI broadcasts, automated job-alert digests where you'd just click "apply" not write back.

                EMAIL METADATA:
                  From:    %s
                  Subject: %s
                  Time:    %s
                  Snippet: %s

                EMAIL BODY (may contain Gmail UI chrome around it — focus on the actual message text):
                %s
                """.formatted(
                        languageDirective(),
                        nullToDash(item.sender()),
                        nullToDash(item.subject()),
                        nullToDash(item.time()),
                        nullToDash(item.snippet()),
                        truncate(emailBody, 4000));
    }

    private static String buildDraftPrompt(InboxItem item, String emailBody, String summary) {
        return """
                Write three short reply drafts to the email below — one per tone.

                LANGUAGE: %s

                CONTENT REQUIREMENTS (apply to all three drafts):
                - Address the actual question or ask. If the sender asked X, the draft answers X.
                  Don't write generic acknowledgements — specifically, NEVER use any of these stock phrases:
                    "I have noted your request"
                    "I will proceed as instructed"
                    "Thank you for your email"
                    "I have received your message"
                    "Noted" (as a one-word reply)
                  Instead, write something only this specific email would prompt.
                - Reference at least one concrete detail from the email body (a name, a date, the subject topic)
                  so the draft couldn't be confused with a reply to a different email.
                - ≤80 words each. Plain text, no markdown, no signature block, no subject line, no meta commentary.
                - Never invent dates, links, file names, prices, attendees, or commitments not in the original email.

                TONE CALIBRATION (the three drafts must read differently):
                - formal:    professional, third-person where natural. Suitable for client / external / senior stakeholder.
                             Salutation + body + closing.
                - friendly:  warm, first-person, conversational. Teammate-level. "Thanks!" / "sure thing" OK.
                - brief:     1–2 sentences max, very direct. Phone-thumb-reply length. No salutation needed.

                Return JSON: { "formal": "...", "friendly": "...", "brief": "..." }

                EMAIL METADATA:
                  From:    %s
                  Subject: %s

                EMAIL BODY:
                %s

                CONTEXT (your one-line summary of this email):
                %s
                """.formatted(
                        languageDirective(),
                        nullToDash(item.sender()),
                        nullToDash(item.subject()),
                        truncate(emailBody, 3000),
                        nullToDash(summary));
    }

    /**
     * Clamp every CTA's {@code type} and {@code priority} to a valid enum name —
     * the LLM happily emits invented values like {@code MESSAGE}, {@code ACTION},
     * {@code INTERNAL-LINK} that would otherwise pass through to the UI raw.
     */
    private static List<Cta> normalizeCtas(List<Cta> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<Cta> out = new ArrayList<>(raw.size());
        for (Cta c : raw) {
            if (c == null || c.text() == null || c.text().isBlank()) continue;
            String type = c.typeEnum().name();      // resolves to OTHER if invalid
            String priority = c.priorityEnum().name(); // resolves to MEDIUM if invalid
            out.add(new Cta(
                    truncate(c.text(), 200),
                    c.dueDate(),
                    type,
                    priority,
                    c.sourceQuote() == null ? null : truncate(c.sourceQuote(), 240)));
        }
        return out;
    }

    private static String nullToDash(String s) { return s == null || s.isBlank() ? "—" : s; }

    /**
     * Strip LLM control/stop tokens that smaller Ollama models leak into output:
     * {@code <DONE>}, {@code <|endoftext|>}, {@code <|im_end|>}, {@code </s>},
     * {@code <eot>}, etc. Also drops stray markdown fences around plain prose.
     * Applied to every text field before we ship the EnrichedEmail back —
     * defense in depth (frontend has the same scrub).
     */
    private static String stripLLMArtifacts(String s) {
        if (s == null) return null;
        String out = s
                .replaceAll("(?i)<\\|?(?:done|endoftext|im_end|im_start|end_of_(?:text|turn)|eos|eot|stop)\\|?>", "")
                .replaceAll("</?s>", "")
                .replaceAll("<DONE>", "")
                .replaceAll("```(?:json|text)?\\s*", "")
                .replaceAll("\\s*```", "")
                .trim();
        return out;
    }

    /** Apply {@link #stripLLMArtifacts} to every CTA text + sourceQuote in place. */
    private static List<Cta> scrubCtas(List<Cta> ctas) {
        if (ctas == null || ctas.isEmpty()) return ctas;
        java.util.List<Cta> out = new java.util.ArrayList<>(ctas.size());
        for (Cta c : ctas) {
            out.add(new Cta(
                    stripLLMArtifacts(c.text()),
                    c.dueDate(),
                    c.type(),
                    c.priority(),
                    stripLLMArtifacts(c.sourceQuote())));
        }
        return out;
    }

    private static ReplyDrafts scrubDrafts(ReplyDrafts d) {
        if (d == null) return null;
        return new ReplyDrafts(
                stripLLMArtifacts(d.formal()),
                stripLLMArtifacts(d.friendly()),
                stripLLMArtifacts(d.brief()));
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
