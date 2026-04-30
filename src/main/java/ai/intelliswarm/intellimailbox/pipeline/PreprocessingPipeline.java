package ai.intelliswarm.intellimailbox.pipeline;

import ai.intelliswarm.intellimailbox.enrichment.EnrichedEmail;
import ai.intelliswarm.intellimailbox.enrichment.EnrichmentService;
import ai.intelliswarm.intellimailbox.gmail.EmailReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Orchestrator: scrape → queue → enrich → publish.
 *
 * <ul>
 *   <li>BrowserTool drives one Chrome page so we MUST process serially. Single-thread executor.</li>
 *   <li>Results land in an in-memory cache keyed by {@link InboxItem#id}. Refreshes that re-list
 *       the same id reuse the cached result — the user doesn't pay LLM cost twice.</li>
 *   <li>Subscribers (one per SSE client) receive each {@link EnrichedEmail} as it lands, plus
 *       a replay of everything already cached when they first connect.</li>
 * </ul>
 */
@Service
public class PreprocessingPipeline {

    private static final Logger logger = LoggerFactory.getLogger(PreprocessingPipeline.class);

    private final EmailReader reader;
    private final EnrichmentService enrichment;

    /** Single-thread executor — BrowserTool drives one Chrome page, can't parallelize. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "intellimailbox-enrich");
        t.setDaemon(true);
        return t;
    });

    /** Cache of already-enriched emails by inbox-position id. */
    private final Map<String, EnrichedEmail> cache = new ConcurrentHashMap<>();

    /** Live SSE subscribers — receive every newly enriched email. */
    private final List<Consumer<EnrichedEmail>> subscribers = new CopyOnWriteArrayList<>();

    /** Tracks which ids currently have an enrichment job in-flight, to avoid duplicate queueing. */
    private final Map<String, AtomicBoolean> inFlight = new ConcurrentHashMap<>();

    public PreprocessingPipeline(EmailReader reader, EnrichmentService enrichment) {
        this.reader = reader;
        this.enrichment = enrichment;
    }

    /**
     * Scrapes the visible inbox and returns the metadata-only listing immediately.
     * For each row that isn't cached and isn't already in flight, queues an enrichment task.
     */
    public List<InboxItem> refresh() {
        List<InboxItem> items = reader.listInbox();
        for (InboxItem item : items) {
            if (cache.containsKey(item.id())) continue;
            queueEnrichment(item);
        }
        return items;
    }

    /** Force re-enrichment of one row even if cached. */
    public void reprocess(String id, InboxItem item) {
        cache.remove(id);
        queueEnrichment(item);
    }

    public java.util.Optional<EnrichedEmail> getCached(String id) {
        return java.util.Optional.ofNullable(cache.get(id));
    }

    public Map<String, EnrichedEmail> snapshot() {
        return Map.copyOf(cache);
    }

    public void subscribe(Consumer<EnrichedEmail> sink) { subscribers.add(sink); }
    public void unsubscribe(Consumer<EnrichedEmail> sink) { subscribers.remove(sink); }

    private void queueEnrichment(InboxItem item) {
        AtomicBoolean lease = inFlight.computeIfAbsent(item.id(), k -> new AtomicBoolean(false));
        if (!lease.compareAndSet(false, true)) {
            return; // already in-flight
        }
        worker.submit(() -> {
            try {
                String body = reader.readEmail(item.id());
                EnrichedEmail enriched = enrichment.enrich(item, body);
                cache.put(item.id(), enriched);
                publish(enriched);
            } catch (Throwable t) {
                logger.warn("Pipeline: unexpected failure for id={}: {}", item.id(), t.toString());
                EnrichedEmail failed = EnrichedEmail.failed(item, t.getClass().getSimpleName(), 0L);
                cache.put(item.id(), failed);
                publish(failed);
            } finally {
                lease.set(false);
            }
        });
    }

    private void publish(EnrichedEmail e) {
        for (Consumer<EnrichedEmail> sub : subscribers) {
            try { sub.accept(e); } catch (Throwable t) {
                logger.debug("Pipeline: subscriber threw, removing — {}", t.toString());
                subscribers.remove(sub);
            }
        }
    }
}
