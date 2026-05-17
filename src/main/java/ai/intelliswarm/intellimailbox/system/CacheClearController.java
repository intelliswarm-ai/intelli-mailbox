package ai.intelliswarm.intellimailbox.system;

import ai.intelliswarm.intellimailbox.chat.InboxIndexer;
import ai.intelliswarm.intellimailbox.pipeline.PreprocessingPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * "Reset AI data" endpoint — wipes everything Intelli-mailbox has derived
 * about the user's inbox: enrichments (summaries, badges, CTAs, drafts),
 * the raw scraped bodies, and the vector embeddings powering chat search.
 *
 * <p>Distinct from {@code /api/chrome/logout} (which wipes Gmail
 * credentials) and from {@code /api/shutdown} (which stops the JVM):
 * this only removes AI-derived data so the next ▶ Process recomputes
 * everything from scratch. Useful when:
 * <ul>
 *   <li>Cached entries look stale or wrong (id-drift artefacts from
 *       earlier app versions)</li>
 *   <li>Reclaiming disk space on a large processed inbox</li>
 *   <li>You changed LLM provider/model and want a clean re-derivation</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/cache")
public class CacheClearController {

    private static final Logger logger = LoggerFactory.getLogger(CacheClearController.class);

    private final PreprocessingPipeline pipeline;
    private final InboxIndexer indexer;

    public CacheClearController(PreprocessingPipeline pipeline, InboxIndexer indexer) {
        this.pipeline = pipeline;
        this.indexer = indexer;
    }

    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clear() {
        logger.info("/api/cache/clear requested");
        // Order matters: clearVectorStore enumerates pipeline ids to drop
        // them from the vector store. Run it BEFORE clearing the pipeline
        // cache, otherwise it has nothing to enumerate.
        int vectors = indexer.clearVectorStore();
        PreprocessingPipeline.ClearResult r = pipeline.clearCache();

        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("enrichmentsCleared", r.enrichments());
        out.put("bodiesCleared", r.bodies());
        out.put("vectorsCleared", vectors);
        out.put("message", "Cleared " + r.enrichments() + " enrichment(s), "
                + r.bodies() + " body(ies), and " + vectors + " vector(s). "
                + "Click ▶ Process all to re-derive everything.");
        return ResponseEntity.ok(out);
    }
}
