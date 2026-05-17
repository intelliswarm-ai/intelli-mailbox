package ai.intelliswarm.intellimailbox.pipeline;

import ai.intelliswarm.intellimailbox.enrichment.EnrichedEmail;
import ai.intelliswarm.intellimailbox.enrichment.EnrichmentService;
import ai.intelliswarm.intellimailbox.gmail.EmailReader;
import ai.intelliswarm.intellimailbox.settings.ExcludedSendersService;
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
    private final ExcludedSendersService excludedSenders;

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

    /** Most recent listing's items keyed by id. Populated on every refresh()
     *  so /reprocess can find the InboxItem without re-scraping Gmail with a
     *  potentially different range. Without this cache, /reprocess(range=7d)
     *  would call refresh() with no range, scrape the unscoped inbox, and
     *  fail to match the user's id format ("1:0", "1:1", …) — returning
     *  queued=false silently. */
    private final Map<String, InboxItem> lastListingById = new ConcurrentHashMap<>();

    private volatile boolean shuttingDown = false;

    private final EnrichmentCacheStore cacheStore;
    private final java.util.concurrent.atomic.AtomicLong dirtyCount =
            new java.util.concurrent.atomic.AtomicLong(0);
    private static final long PERSIST_EVERY = 3;

    private record EnrichJob(InboxItem item, String body) {}

    public PreprocessingPipeline(EmailReader reader,
                                 EnrichmentService enrichment,
                                 ExcludedSendersService excludedSenders,
                                 EnrichmentCacheStore cacheStore) {
        this.reader = reader;
        this.enrichment = enrichment;
        this.excludedSenders = excludedSenders;
        this.cacheStore = cacheStore;
        // Restore prior session's work so a restart picks up immediately
        // without re-paying LLM cost. Bodies are restored too so the
        // /details endpoint serves the modal without re-scraping.
        cache.putAll(cacheStore.load());
        bodyCache.putAll(cacheStore.loadBodies());
        // Boot the enricher loop straight away — it sits on handoff.take() until
        // the reader produces work, so it's safe to start before any items arrive.
        enricherExec.submit(this::enricherLoop);
    }

    @PreDestroy
    private void shutdown() {
        shuttingDown = true;
        readerExec.shutdownNow();
        enricherExec.shutdownNow();
        // Final flush so anything that landed since the last persist is
        // captured. Cheap on shutdown — at most one disk write.
        cacheStore.save(snapshot(), Map.copyOf(bodyCache));
    }

    private void maybePersist() {
        if (dirtyCount.incrementAndGet() % PERSIST_EVERY == 0) {
            cacheStore.save(snapshot(), Map.copyOf(bodyCache));
        }
    }

    /**
     * Scrapes the visible inbox and returns the metadata-only listing immediately.
     * For each row that isn't cached and isn't already in flight, queues a body-read
     * task on the reader stage. The enricher picks up jobs as fast as it can drain
     * the LLM (one at a time).
     */
    public List<InboxItem> refresh() {
        return refresh(null);
    }

    /**
     * Date-range-scoped refresh. {@code range} is a Gmail
     * {@code newer_than:} duration (e.g. {@code "1d"}, {@code "7d"},
     * {@code "30d"}). When non-blank, the reader navigates Gmail's SPA to
     * the corresponding search ({@code in:inbox newer_than:<range>}) and
     * scrapes its results — letting the user pull older emails than the
     * default inbox listing covers.
     */
    public List<InboxItem> refresh(String range) {
        // Listing only — does NOT enqueue enrichment. The user reviews the
        // list (and optionally right-clicks senders to exclude) before
        // explicitly opting in to processing via /api/inbox/process.
        // Rationale: LLM tokens are a real cost; auto-processing every refresh
        // burned tokens on rows the user wouldn't have asked to analyze.
        List<InboxItem> items = reader.listInbox(range);
        // Replace the lookup map so /reprocess can find items by id without
        // re-scraping Gmail. Built from this listing exactly, so the ids in
        // the map are guaranteed to match what the frontend just received.
        lastListingById.clear();
        for (InboxItem it : items) lastListingById.put(it.id(), it);

        // Self-heal stale cache entries: ids are DOM-position based
        // ("page:idx"), so "1:5" today can point at a different email than
        // "1:5" yesterday. Any cached entry whose stored subject/sender no
        // longer match the freshly-scraped InboxItem at the same id is
        // mixing metadata from two different emails — evict it now so the
        // SSE replay below doesn't ship garbage to the UI. Body cache is
        // evicted in lockstep so the email-detail modal doesn't show a
        // different email's body either.
        evictDriftedEntries(items);
        return items;
    }

    /** Walk the freshly-listed items, evict any cached entry where the new
     *  InboxItem's subject+sender disagree with what we have on file for
     *  that same id. Persists once at the end if anything changed. */
    private void evictDriftedEntries(List<InboxItem> items) {
        int evicted = 0;
        for (InboxItem item : items) {
            EnrichedEmail existing = cache.get(item.id());
            if (existing == null) continue;
            if (sameEmail(existing.item(), item)) continue;
            logger.info("Cache drift: id={} was '{}' / '{}', now '{}' / '{}' — evicting",
                    item.id(),
                    existing.item() == null ? "?" : existing.item().sender(),
                    existing.item() == null ? "?" : existing.item().subject(),
                    item.sender(), item.subject());
            cache.remove(item.id());
            bodyCache.remove(item.id());
            evicted++;
        }
        if (evicted > 0) {
            cacheStore.save(snapshot(), Map.copyOf(bodyCache));
        }
    }

    /** Look up an item from the most recent {@link #refresh(String)} result.
     *  Empty after JVM start until the first refresh runs. */
    public java.util.Optional<InboxItem> findInLastListing(String id) {
        return java.util.Optional.ofNullable(lastListingById.get(id));
    }

    /**
     * Trigger enrichment for every item in {@code items} that isn't on the
     * excluded-senders list and isn't already cached. Returns the count of
     * rows actually enqueued (excludes already-cached and excluded ones).
     * Called from the explicit "▶ Process inbox" path — never automatically
     * by the listing endpoint.
     */
    public int processItems(List<InboxItem> items) {
        int queued = 0, skipped = 0, alreadyCached = 0, stale = 0;
        for (InboxItem item : items) {
            if (excludedSenders.isExcluded(item.sender())) { skipped++; continue; }
            EnrichedEmail existing = cache.get(item.id());
            if (existing != null) {
                // Same id can map to a DIFFERENT email across sessions because
                // ids are DOM-position based ("page:idx") — when Gmail reorders
                // or the range changes, "1:5" today may be a wholly different
                // email than "1:5" yesterday. Detect that here by comparing
                // the current InboxItem's subject + sender to what we cached,
                // and evict + re-enqueue when they don't agree.
                if (sameEmail(existing.item(), item)) {
                    alreadyCached++;
                    continue;
                }
                stale++;
                cache.remove(item.id());
                bodyCache.remove(item.id());
                logger.info("processItems: id={} drifted — was '{}' / '{}', now '{}' / '{}' — re-enqueueing",
                        item.id(),
                        existing.item() == null ? "?" : existing.item().sender(),
                        existing.item() == null ? "?" : existing.item().subject(),
                        item.sender(), item.subject());
            }
            queueEnrichment(item);
            queued++;
        }
        if (stale > 0) {
            // Persist immediately so we don't restart with the same bad rows.
            cacheStore.save(snapshot(), Map.copyOf(bodyCache));
        }
        logger.info("processItems: queued={} skippedExcluded={} alreadyCached={} staleEvicted={} (total {})",
                queued, skipped, alreadyCached, stale, items.size());
        return queued;
    }

    /** Two InboxItems are "the same email" if their subject AND sender both
     *  match (case-insensitive, whitespace-trimmed). Time and snippet are
     *  intentionally ignored — Gmail rewrites relative timestamps ("3h ago"
     *  → "5h ago") on every scrape, and snippets vary with the rendered
     *  inbox column width. */
    private static boolean sameEmail(InboxItem a, InboxItem b) {
        if (a == null || b == null) return false;
        return normalize(a.subject()).equals(normalize(b.subject()))
            && normalize(a.sender()).equals(normalize(b.sender()));
    }
    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    /** Force re-enrichment of one row even if cached. */
    public void reprocess(String id, InboxItem item) {
        cache.remove(id);
        queueEnrichment(item);
    }

    public java.util.Optional<EnrichedEmail> getCached(String id) {
        return java.util.Optional.ofNullable(cache.get(id));
    }

    /**
     * Wipe every cached enrichment + raw body and persist empty files to
     * disk so the next startup starts clean. Does NOT touch the vector
     * store — call {@code InboxIndexer.clearVectorStore()} for that.
     * Returns the counts that were dropped so the UI can show "Cleared 174
     * enrichments and 174 bodies."
     */
    public ClearResult clearCache() {
        int enrichments = cache.size();
        int bodies = bodyCache.size();
        cache.clear();
        bodyCache.clear();
        lastListingById.clear();
        // Persist empty so a restart doesn't restore the just-cleared state.
        cacheStore.save(java.util.Map.of(), java.util.Map.of());
        logger.info("PreprocessingPipeline: cache cleared ({} enrichments, {} bodies)",
                enrichments, bodies);
        return new ClearResult(enrichments, bodies);
    }

    public record ClearResult(int enrichments, int bodies) {}

    /**
     * Drops every cached enrichment + scraped body whose original sender
     * matches the supplied (case-insensitive) string. Called after the user
     * adds a sender to the "do not process" list so that:
     *   - any in-flight enrichment's eventual result is discarded on land
     *   - the row stops carrying a stale AI summary
     *   - the next refresh skips re-enrichment (refresh() filter handles that)
     * Does nothing if {@code sender} is null/blank.
     */
    public int evictBySender(String sender) {
        if (sender == null) return 0;
        String norm = sender.trim().toLowerCase();
        if (norm.isEmpty()) return 0;
        int removed = 0;
        for (var entry : cache.entrySet()) {
            EnrichedEmail e = entry.getValue();
            if (e == null || e.item() == null) continue;
            String s = e.item().sender();
            if (s != null && s.trim().toLowerCase().equals(norm)) {
                cache.remove(entry.getKey());
                bodyCache.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            logger.info("Evicted {} cached entries for excluded sender '{}'", removed, sender);
        }
        return removed;
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

    /**
     * Returns ids whose enrichment lease is currently held — i.e. the backend
     * has them queued or actively in flight. Powers the UI's "see exactly what's
     * being processed right now" verification line. Excludes ids whose lease
     * was already released (those are either fully enriched or failed and
     * surfaced via {@link #cache}).
     */
    public java.util.List<String> inFlightIds() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (var e : inFlight.entrySet()) {
            if (e.getValue() != null && e.getValue().get()) out.add(e.getKey());
        }
        return out;
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
                String body = reader.readEmail(item);
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
            // Belt-and-braces: re-check the excluded list at dequeue time. If
            // the user clicked "Exclude sender" while this job sat in the
            // single-slot handoff queue, drop it before paying any LLM cost.
            if (excludedSenders.isExcluded(job.item.sender())) {
                logger.info("Enricher: skipping id={} sender now on excluded list", job.item.id());
                AtomicBoolean lease = inFlight.get(job.item.id());
                if (lease != null) lease.set(false);
                continue;
            }
            try {
                enrichment.enrichStreaming(job.item, job.body)
                        .doOnNext(evt -> {
                            if (evt instanceof PipelineEvent.Enriched e) {
                                cache.put(job.item.id(), e.email());
                                maybePersist();
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
