package ai.intelliswarm.intellimailbox.pipeline;

import ai.intelliswarm.intellimailbox.enrichment.EnrichedEmail;
import ai.intelliswarm.intellimailbox.enrichment.EnrichmentService;
import ai.intelliswarm.intellimailbox.gmail.EmailReader;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Orchestrator: scrape → queue → enrich → publish.
 *
 * <p>Two single-thread stages with a one-slot handoff between them:
 * <ol>
 *   <li><b>reader</b> — drives BrowserTool to scrape one email's body</li>
 *   <li><b>enricher</b> — drives the LLM to produce summary/badges/CTAs/drafts</li>
 * </ol>
 *
 * <p>The two stages use disjoint resources (Chrome vs Ollama/OpenAI), so they
 * can run concurrently. The {@link SynchronousQueue} between them means the
 * reader is always exactly <em>one email ahead</em> of the enricher: while the
 * LLM is chewing on email N, email N+1's body is being scraped; the reader
 * then blocks on {@code handoff.put} until the LLM finishes and the enricher
 * picks up the next job. Result: no piled-up work, no parallel LLM calls (so
 * the UI stays responsive), but no wasted time either.
 *
 * <ul>
 *   <li>Results land in an in-memory cache keyed by {@link InboxItem#id}. Refreshes that re-list
 *       the same id reuse the cached result — the user doesn't pay LLM cost twice.</li>
 *   <li>Subscribers (one per SSE client) receive each {@link PipelineEvent} as it lands —
 *       many {@link PipelineEvent.SummaryDelta} per email while the LLM streams the prose
 *       summary, then a single terminal {@link PipelineEvent.Enriched} carrying the full
 *       {@link EnrichedEmail}. On reconnect, the cache is replayed as
 *       {@code Enriched} events so a late-arriving client catches up.</li>
 * </ul>
 *
 * @since 1.2.0  (pipelined reader/enricher; was a single worker thread in 1.1.x)
 */
@Service
public class PreprocessingPipeline {

    private static final Logger logger = LoggerFactory.getLogger(PreprocessingPipeline.class);

    private final EmailReader reader;
    private final EnrichmentService enrichment;

    /** Reads email bodies from Chrome. Single thread because BrowserTool drives one Page. */
    private final ExecutorService readerExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "intellimailbox-reader");
        t.setDaemon(true);
        return t;
    });

    /** Runs the enrichment LLM. Single thread so we don't fan out concurrent
     *  Ollama/OpenAI calls from the same browser scrape — keeps the UI responsive. */
    private final ExecutorService enricherExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "intellimailbox-enricher");
        t.setDaemon(true);
        return t;
    });

    /** Zero-capacity handoff: reader blocks on {@code put} until enricher does
     *  {@code take}. This is the gate that bounds in-flight work to "one read
     *  ahead of one LLM" — exactly N+1 pipelining, no buffer. */
    private final SynchronousQueue<EnrichJob> handoff = new SynchronousQueue<>();

    /** Cache of already-enriched emails by inbox-position id. */
    private final Map<String, EnrichedEmail> cache = new ConcurrentHashMap<>();

    /** Cache of raw email bodies (the BrowserTool scrape result), keyed by id.
     *  Powers the {@code /api/email/{id}/details} endpoint behind the modal —
     *  we already have the body in memory after the read step, no point making
     *  the user wait for another scrape when they click a row. */
    private final Map<String, String> bodyCache = new ConcurrentHashMap<>();

    /** Live SSE subscribers — receive every {@link PipelineEvent} (deltas + final). */
    private final List<Consumer<PipelineEvent>> subscribers = new CopyOnWriteArrayList<>();

    /** Tracks which ids are queued/in-flight, to avoid duplicate work. Lease is
     *  released when the enricher publishes the terminal event for that id (or
     *  when the reader's read step fails). */
    private final Map<String, AtomicBoolean> inFlight = new ConcurrentHashMap<>();

    private volatile boolean shuttingDown = false;

    private record EnrichJob(InboxItem item, String body) {}

    public PreprocessingPipeline(EmailReader reader, EnrichmentService enrichment) {
        this.reader = reader;
        this.enrichment = enrichment;
        // Boot the enricher loop straight away — it sits on handoff.take() until
        // the reader produces work, so it's safe to start before any items arrive.
        enricherExec.submit(this::enricherLoop);
    }

    @PreDestroy
    private void shutdown() {
        shuttingDown = true;
        readerExec.shutdownNow();
        enricherExec.shutdownNow();
    }

    /**
     * Scrapes the visible inbox and returns the metadata-only listing immediately.
     * For each row that isn't cached and isn't already in flight, queues a body-read
     * task on the reader stage. The enricher picks up jobs as fast as it can drain
     * the LLM (one at a time).
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

    public java.util.Optional<String> getCachedBody(String id) {
        return java.util.Optional.ofNullable(bodyCache.get(id));
    }

    public Map<String, EnrichedEmail> snapshot() {
        return Map.copyOf(cache);
    }

    /**
     * Snapshot of every id we've scraped a body for — superset of {@link #snapshot()}'s
     * keys, since the reader runs ahead of the enricher (an id may have a cached body
     * even before its EnrichedEmail lands). Used by the Debug view so raw scraped
     * bodies surface immediately, not only after enrichment finishes.
     */
    public java.util.Set<String> bodyCacheIds() {
        return java.util.Set.copyOf(bodyCache.keySet());
    }

    public void subscribe(Consumer<PipelineEvent> sink) { subscribers.add(sink); }
    public void unsubscribe(Consumer<PipelineEvent> sink) { subscribers.remove(sink); }

    private void queueEnrichment(InboxItem item) {
        AtomicBoolean lease = inFlight.computeIfAbsent(item.id(), k -> new AtomicBoolean(false));
        if (!lease.compareAndSet(false, true)) {
            return; // already queued/in-flight
        }
        readerExec.submit(() -> {
            try {
                String body = reader.readEmail(item.id());
                // Stash the raw body so the /details endpoint can serve it
                // without re-scraping when the user opens the modal.
                if (body != null && !body.isBlank()) {
                    bodyCache.put(item.id(), body);
                }
                // Pipeline gate: blocks until the enricher does handoff.take().
                // While this blocks, EmailReader's monitor is FREE — listInbox
                // requests from the web thread can proceed normally.
                handoff.put(new EnrichJob(item, body));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                lease.set(false);
            } catch (Throwable t) {
                logger.warn("Reader: read failed for id={}: {}", item.id(), t.toString());
                EnrichedEmail failed = EnrichedEmail.failed(item, t.getClass().getSimpleName(), 0L);
                cache.put(item.id(), failed);
                publish(new PipelineEvent.Enriched(item.id(), failed));
                lease.set(false);
            }
        });
    }

    /**
     * The enricher loop — runs forever on its own thread, draining the handoff
     * queue. Exactly one LLM call is in flight at a time (the {@code blockLast}
     * waits for the streaming Flux to complete before we go back for the next job).
     */
    private void enricherLoop() {
        while (!shuttingDown && !Thread.currentThread().isInterrupted()) {
            EnrichJob job;
            try {
                job = handoff.take();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                enrichment.enrichStreaming(job.item, job.body)
                        .doOnNext(evt -> {
                            if (evt instanceof PipelineEvent.Enriched e) {
                                cache.put(job.item.id(), e.email());
                            }
                            publish(evt);
                        })
                        .blockLast(Duration.ofMinutes(10));
            } catch (Throwable t) {
                logger.warn("Enricher: LLM failure for id={}: {}", job.item.id(), t.toString());
                EnrichedEmail failed = EnrichedEmail.failed(job.item, t.getClass().getSimpleName(), 0L);
                cache.put(job.item.id(), failed);
                publish(new PipelineEvent.Enriched(job.item.id(), failed));
            } finally {
                AtomicBoolean lease = inFlight.get(job.item.id());
                if (lease != null) lease.set(false);
            }
        }
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
