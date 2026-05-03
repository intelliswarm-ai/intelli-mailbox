package ai.intelliswarm.intellimailbox.settings;

import ai.intelliswarm.intellimailbox.pipeline.PreprocessingPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST surface for the "do not process" sender list.
 *
 * <pre>
 *   GET    /api/excluded-senders         -> { senders: [...] }
 *   POST   /api/excluded-senders { sender: "..." } -> { added: bool, sender, total }
 *   DELETE /api/excluded-senders/{sender}  (URL-encoded) -> { removed: bool, sender, total }
 * </pre>
 */
@RestController
@RequestMapping("/api/excluded-senders")
public class ExcludedSendersController {

    private static final Logger logger = LoggerFactory.getLogger(ExcludedSendersController.class);

    private final ExcludedSendersService service;
    private final PreprocessingPipeline pipeline;

    public ExcludedSendersController(ExcludedSendersService service,
                                     PreprocessingPipeline pipeline) {
        this.service = service;
        this.pipeline = pipeline;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<String> senders = service.list();
        return Map.of("senders", senders, "total", senders.size());
    }

    @PostMapping(consumes = { MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE })
    public ResponseEntity<Map<String, Object>> add(@RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("sender");
        String sender = raw instanceof String s ? s : null;
        boolean added = service.add(sender);
        // Drop any cached / mid-flight enrichments for this sender so we
        // don't keep paying LLM cost for work the user just opted out of.
        // Pipeline.evictBySender is a no-op for null/blank, so this is safe
        // even when nothing was added (idempotent).
        int evicted = pipeline.evictBySender(sender);
        if (added) logger.info("Excluded sender added: {} (evicted {} cached)", sender, evicted);
        HashMap<String, Object> resp = new HashMap<>();
        resp.put("added", added);
        resp.put("sender", sender == null ? "" : sender);
        resp.put("total", service.list().size());
        resp.put("evicted", evicted);
        return ResponseEntity.ok(resp);
    }

    @DeleteMapping("/{sender}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable("sender") String sender) {
        boolean removed = service.remove(sender);
        if (removed) logger.info("Excluded sender removed: {}", sender);
        return ResponseEntity.ok(Map.of(
                "removed", removed,
                "sender", sender,
                "total", service.list().size()));
    }
}
