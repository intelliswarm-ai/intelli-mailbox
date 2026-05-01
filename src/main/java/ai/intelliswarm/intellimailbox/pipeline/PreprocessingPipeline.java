package ai.intelliswarm.intellimailbox.pipeline;

import ai.intelliswarm.intellimailbox.enrichment.EnrichedEmail;
import ai.intelliswarm.intellimailbox.enrichment.EnrichmentService;
import ai.intelliswarm.intellimailbox.gmail.EmailReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
 *   <li>Subscribers (one per SSE client) receive each {@link PipelineEvent} as it lands —
 *       many {@link PipelineEvent.SummaryDelta} per email while the LLM streams the prose
 *       summary, then a single terminal {@link PipelineEvent.Enriched} carrying the full
 *       {@link EnrichedEmail}. On reconnect, the cache is replayed as
 *       {@code Enriched} events so a late-arriving client catches up.</li>
 * </ul>
 *
 * @since 1.1.0  (was {@code Consumer<EnrichedEmail>} subscribers in 1.0.x)
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

    /** Live SSE subscribers — receive every {@link PipelineEvent} (deltas + final). */
    private final List<Consumer<PipelineEvent>> subscribers = new CopyOnWriteArrayList<>();

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

    public void subscribe(Consumer<PipelineEvent> sink) { subscribers.add(sink); }
    public void unsubscribe(Consumer<PipelineEvent> sink) { subscribers.remove(sink); }

    private void queueEnrichment(InboxItem item) {
        AtomicBoolean lease = inFlight.computeIfAbsent(item.id(), k -> new AtomicBoolean(false));
        if (!lease.compareAndSet(false, true)) {
            return; // already in-flight
        }
        worker.submit(() -> {
            try {
                String body = reader.readEmail(item.id());
                // Subscribe synchronously and block until the Flux completes —
                // the worker is single-threaded by design, and we want each
                // email's events fully delivered before the next one starts.
                // SummaryDelta events forward straight to subscribers; the
                // terminal Enriched event also lands in the cache.
                enrichment.enrichStreaming(item, body)
                        .doOnNext(evt -> {
                            if (evt instanceof PipelineEvent.Enriched e) {
                                cache.put(item.id(), e.email());
                            }
                            publish(evt);
                        })
                        .blockLast(Duration.ofMinutes(10));
            } catch (Throwable t) {
                logger.warn("Pipeline: unexpected failure for id={}: {}", item.id(), t.toString());
                EnrichedEmail failed = EnrichedEmail.failed(item, t.getClass().getSimpleName(), 0L);
                cache.put(item.id(), failed);
                publish(new PipelineEvent.Enriched(item.id(), failed));
            } finally {
                lease.set(false);
            }
        });
    }

    private void publish(PipelineEvent e) {
        for (Consumer<PipelineEvent> sub : subscribers) {
            try { sub.accept(e); } catch (Throwable t) {
                logger.debug("Pipeline: subscriber threw, removing — {}", t.toString());
                subscribers.remove(sub);
            }
        }
    }
}
