package ai.intelliswarm.intellimailbox.web;

import ai.intelliswarm.intellimailbox.enrichment.EnrichedEmail;
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

    public InboxController(PreprocessingPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @GetMapping("/inbox")
    public List<InboxItem> inbox() {
        long t0 = System.currentTimeMillis();
        List<InboxItem> items = pipeline.refresh();
        logger.info("/api/inbox → {} items in {}ms (enrichment queued in background)",
                items.size(), System.currentTimeMillis() - t0);
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

        Runnable cleanup = () -> pipeline.unsubscribe(sink);
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
    public ResponseEntity<Map<String, Object>> reprocess(@PathVariable String id) {
        // We only have the inbox-position id; refresh re-scrapes and finds the matching item.
        for (InboxItem item : pipeline.refresh()) {
            if (item.id().equals(id)) {
                pipeline.reprocess(id, item);
                return ResponseEntity.ok(Map.of("queued", true, "id", id));
            }
        }
        return ResponseEntity.ok(Map.of("queued", false, "id", id, "reason", "id not in current inbox"));
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "cached", pipeline.snapshot().size());
    }
}
