package ai.intelliswarm.intellimailbox.web;

import ai.intelliswarm.intellimailbox.enrichment.EnrichedEmail;
import ai.intelliswarm.intellimailbox.enrichment.ReplyDrafts;
import ai.intelliswarm.intellimailbox.gmail.EmailReader;
import ai.intelliswarm.intellimailbox.pipeline.InboxItem;
import ai.intelliswarm.intellimailbox.pipeline.PipelineEvent;
import ai.intelliswarm.intellimailbox.pipeline.PreprocessingPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * REST + SSE for the IntelliMailbox UI.
 *
 * <pre>
 *   GET  /api/inbox            → kick off preprocessing for the current Gmail inbox,
 *                                return InboxItem[] immediately (no enrichment yet)
 *   GET  /api/inbox/stream     → SSE stream of {@link EnrichedEmail}s, replays cache on connect
 *   POST /api/email/{id}/reprocess → force re-enrichment of one row
 *   GET  /api/health
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class InboxController {

    private static final Logger logger = LoggerFactory.getLogger(InboxController.class);

    private final PreprocessingPipeline pipeline;
    private final EmailReader reader;
    private final ai.intelliswarm.intellimailbox.system.IdleShutdownManager idleShutdown;

    public InboxController(PreprocessingPipeline pipeline,
                           EmailReader reader,
                           ai.intelliswarm.intellimailbox.system.IdleShutdownManager idleShutdown) {
        this.pipeline = pipeline;
        this.reader = reader;
        this.idleShutdown = idleShutdown;
    }

    @GetMapping("/inbox")
    public List<InboxItem> inbox(
            @org.springframework.web.bind.annotation.RequestParam(value = "range", required = false) String range) {
        long t0 = System.currentTimeMillis();
        // Default the unset case to "1d" — the user-facing default the
        // dropdown ships with. Gmail's `newer_than:1d` returns inbox
        // emails from the last 24 hours, which is the most useful first
        // experience without a giant initial enrichment queue.
        String effectiveRange = (range == null || range.isBlank()) ? "1d" : range.trim().toLowerCase();
        // Sentinel "all" lets the caller explicitly ask for the current
        // unscoped inbox listing (the legacy behaviour).
        String passToReader = "all".equals(effectiveRange) ? null : effectiveRange;
        List<InboxItem> items = pipeline.refresh(passToReader);
        logger.info("/api/inbox(range={}) → {} items in {}ms (enrichment queued in background)",
                effectiveRange, items.size(), System.currentTimeMillis() - t0);
        return items;
    }

    @GetMapping(path = "/inbox/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        // No timeout — SSE stays open as long as the client wants. Spring's default
        // of 30s would prematurely tear down a healthy stream.
        SseEmitter emitter = new SseEmitter(0L);

        // Maps PipelineEvent subtypes onto SSE event names — frontend listens with
        // addEventListener('summary_delta', …) / ('email', …).
        Consumer<PipelineEvent> sink = e -> {
            try {
                switch (e) {
                    case PipelineEvent.SummaryDelta d ->
                            emitter.send(SseEmitter.event().name("summary_delta").data(d));
                    case PipelineEvent.Enriched x ->
                            // Wire the EnrichedEmail directly so frontends that key
                            // off { id, summary, badges, … } don't need to unwrap.
                            emitter.send(SseEmitter.event().name("email").data(x.email()));
                }
            } catch (IOException io) {
                emitter.completeWithError(io);
            }
        };
        pipeline.subscribe(sink);
        // Tell the IdleShutdownManager a browser is here — cancels any
        // pending "no clients, shutting down" timer.
        idleShutdown.clientConnected();

        Runnable cleanup = () -> {
            pipeline.unsubscribe(sink);
            // Tell the IdleShutdownManager this browser is gone. If it was
            // the last one, the manager will start the grace-period timer.
            idleShutdown.clientDisconnected();
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());

        // Replay everything already cached as `email` events so a late-arriving
        // client catches up. Cached entries have full summary text already, so
        // we don't replay synthetic SummaryDelta events for them — the UI just
        // renders the final card directly.
        Map<String, EnrichedEmail> snap = pipeline.snapshot();
        for (EnrichedEmail e : snap.values()) {
            try {
                emitter.send(SseEmitter.event().name("email").data(e));
            } catch (IOException io) {
                emitter.completeWithError(io);
                return emitter;
            }
        }
        return emitter;
    }

    @PostMapping("/email/{id}/reprocess")
    public ResponseEntity<Map<String, Object>> reprocess(@PathVariable("id") String id) {
        // We only have the inbox-position id; refresh re-scrapes and finds the matching item.
        for (InboxItem item : pipeline.refresh()) {
            if (item.id().equals(id)) {
                pipeline.reprocess(id, item);
                return ResponseEntity.ok(Map.of("queued", true, "id", id));
            }
        }
        return ResponseEntity.ok(Map.of("queued", false, "id", id, "reason", "id not in current inbox"));
    }

    /**
     * Inject one of the cached reply drafts into Gmail's compose box for the
     * given email. The user reviews and sends from Chrome — we never auto-send.
     *
     * <p>Body: {@code { "tone": "formal" | "friendly" | "brief" }} (optional,
     * defaults to {@code formal}). Returns {@code { ok: boolean, id, tone, reason? }}.
     * Whole body is wrapped in a top-level try/catch so any failure surfaces as a
     * 200 with {@code ok=false} + a {@code reason} string the UI can show — never
     * a 500. That keeps the button's failure label informative ({@code "✗ <reason>"})
     * instead of generic {@code "✗ Failed"}.
     */
    @PostMapping(path = "/email/{id}/inject-draft",
                 consumes = { MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE })
    public ResponseEntity<Map<String, Object>> injectDraft(
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body) {
        // HashMap (not Map.of) so null values never NPE.
        java.util.HashMap<String, Object> resp = new java.util.HashMap<>();
        resp.put("id", id);
        String tone = "formal";
        try {
            Object rawTone = body == null ? null : body.get("tone");
            if (rawTone instanceof String s && !s.isBlank()) tone = s.trim().toLowerCase();
            resp.put("tone", tone);
            logger.info("/api/email/{}/inject-draft (tone={})", id, tone);

            EnrichedEmail cached = pipeline.getCached(id).orElse(null);
            if (cached == null) {
                resp.put("ok", false);
                resp.put("reason", "email not in cache — refresh the inbox first");
                return ResponseEntity.ok(resp);
            }
            if (cached.failed()) {
                resp.put("ok", false);
                resp.put("reason", "this email's enrichment failed");
                return ResponseEntity.ok(resp);
            }
            ReplyDrafts drafts = cached.replyDrafts();
            if (drafts == null) {
                resp.put("ok", false);
                resp.put("reason", "no drafts available — needsReply was false for this email");
                return ResponseEntity.ok(resp);
            }
            String draft = switch (tone) {
                case "friendly" -> drafts.friendly();
                case "brief"    -> drafts.brief();
                default         -> drafts.formal();
            };
            if (draft == null || draft.isBlank()) {
                resp.put("ok", false);
                resp.put("reason", tone + " draft is empty");
                return ResponseEntity.ok(resp);
            }
            boolean ok = reader.injectReply(id, draft);
            resp.put("ok", ok);
            if (!ok) resp.put("reason", "Gmail DOM didn't respond — selector may have rotated");
            return ResponseEntity.ok(resp);
        } catch (Throwable t) {
            // Top-level catch: any unexpected error becomes a tidy 200 with reason,
            // not a Spring 500 with a stack trace.
            logger.warn("/api/email/{}/inject-draft → unexpected: {}", id, t.toString(), t);
            resp.putIfAbsent("tone", tone);
            resp.put("ok", false);
            resp.put("reason", t.getClass().getSimpleName() + ": "
                    + (t.getMessage() == null ? "(no message)" : t.getMessage()));
            return ResponseEntity.ok(resp);
        }
    }

    /**
     * Returns everything the email-detail modal needs in one shot:
     * the cached enrichment, plus the raw body the BrowserTool scraped.
     * Both come from in-memory caches populated during enrichment, so this
     * is a fast read — never re-scrapes Gmail.
     */
    @GetMapping("/email/{id}/details")
    public ResponseEntity<Map<String, Object>> details(@PathVariable("id") String id) {
        java.util.HashMap<String, Object> out = new java.util.HashMap<>();
        out.put("id", id);
        EnrichedEmail e = pipeline.getCached(id).orElse(null);
        if (e == null) {
            out.put("ok", false);
            out.put("reason", "not in cache — refresh the inbox first");
            return ResponseEntity.ok(out);
        }
        out.put("ok", true);
        out.put("enriched", e);
        out.put("body", pipeline.getCachedBody(id).orElse(""));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "cached", pipeline.snapshot().size());
    }

    /**
     * Debug-view endpoint: returns the FULL raw data we have per email — the
     * InboxItem metadata Gmail's listing surfaced PLUS the verbatim body bytes
     * we scraped. Powers the in-app "Debug" view tab so power users can audit
     * exactly what the AI received before any processing happened.
     *
     * <p>Distinct from {@link #cacheStats()} (which omits raw bodies for size).
     * Distinct from {@code /api/email/{id}/details} (which is for a single id
     * and bundles enriched output too). This one is the one-call dump.
     */
    @GetMapping("/debug/cache")
    public Map<String, Object> debugCache() {
        Map<String, EnrichedEmail> snap = pipeline.snapshot();
        java.util.Set<String> bodyIds = pipeline.bodyCacheIds();
        // Union: every id we've scraped a body for OR enriched. Keeps mid-flight
        // rows visible (body cached, enrichment still running) in the Debug view.
        java.util.LinkedHashSet<String> allIds = new java.util.LinkedHashSet<>();
        allIds.addAll(snap.keySet());
        allIds.addAll(bodyIds);

        java.util.List<Map<String, Object>> entries = new java.util.ArrayList<>(allIds.size());
        for (String id : allIds) {
            EnrichedEmail email = snap.get(id);
            InboxItem item = email == null ? null : email.item();
            String body = pipeline.getCachedBody(id).orElse(null);

            java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", id);
            java.util.LinkedHashMap<String, Object> rawItem = new java.util.LinkedHashMap<>();
            rawItem.put("id",      item == null ? id : item.id());
            rawItem.put("sender",  item == null ? null : item.sender());
            rawItem.put("subject", item == null ? null : item.subject());
            rawItem.put("snippet", item == null ? null : item.snippet());
            rawItem.put("time",    item == null ? null : item.time());
            row.put("rawItem", rawItem);
            row.put("rawBody", body == null ? "" : body);
            row.put("rawBodyChars", body == null ? 0 : body.length());
            row.put("bodyCached", body != null && !body.isBlank());
            // Lifecycle stage — lets the UI badge the row accurately:
            //   "scraped"  → body in cache, enrichment not yet finished
            //   "enriched" → both body and EnrichedEmail present, success
            //   "failed"   → enrichment finished but errored out
            String stage = email == null ? "scraped"
                         : (email.failed() ? "failed" : "enriched");
            row.put("stage", stage);
            row.put("processedMs", email == null ? 0L : email.processedMs());
            row.put("failed", email != null && email.failed());
            row.put("badges", email == null ? java.util.List.of() : email.badges());
            row.put("ctaCount", email == null || email.ctas() == null ? 0 : email.ctas().size());
            entries.add(row);
        }
        return Map.of("ok", true, "totalEmails", entries.size(), "entries", entries);
    }

    /**
     * Transparency endpoint: enumerate everything Intelli-mailbox is holding in
     * its in-memory cache. Used by the UI to surface "what we have" so users can
     * verify the AI's analysis traces back to actual scraped email content. No
     * persistence — when the JVM stops, this state is gone.
     */
    @GetMapping("/cache/stats")
    public Map<String, Object> cacheStats() {
        Map<String, EnrichedEmail> snap = pipeline.snapshot();
        java.util.List<Map<String, Object>> entries = new java.util.ArrayList<>(snap.size());
        long totalBodyChars = 0L;
        for (Map.Entry<String, EnrichedEmail> e : snap.entrySet()) {
            EnrichedEmail email = e.getValue();
            int bodyLen = pipeline.getCachedBody(e.getKey()).map(String::length).orElse(0);
            totalBodyChars += bodyLen;
            java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", e.getKey());
            row.put("sender", email.item() == null ? "" : email.item().sender());
            row.put("subject", email.item() == null ? "" : email.item().subject());
            row.put("processedMs", email.processedMs());
            row.put("badges", email.badges());
            row.put("ctaCount", email.ctas() == null ? 0 : email.ctas().size());
            row.put("hasReplyDrafts", email.replyDrafts() != null);
            row.put("phishingSuspected", email.phishingSuspected());
            row.put("failed", email.failed());
            row.put("bodyChars", bodyLen);
            entries.add(row);
        }
        return Map.of(
                "ok", true,
                "totalEmails", snap.size(),
                "totalBodyChars", totalBodyChars,
                "entries", entries
        );
    }
}
