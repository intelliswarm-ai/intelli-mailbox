package ai.intelliswarm.intellimailbox.chat;

import ai.intelliswarm.intellimailbox.enrichment.EnrichedEmail;
import ai.intelliswarm.intellimailbox.pipeline.PipelineEvent;
import ai.intelliswarm.intellimailbox.pipeline.PreprocessingPipeline;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Embeds each enriched email into the {@link SimpleVectorStore} as soon as the
 * preprocessing pipeline emits a terminal {@link PipelineEvent.Enriched}.
 *
 * <p>Single-thread executor so embedding never blocks the enrichment pipeline
 * (embedding ⇒ Ollama, same backend as chat enrichment — serializing keeps
 * Ollama from competing with itself). Failures are silent at the per-email
 * level: a missed embedding means that one email won't be retrievable via
 * chat, but everything else still works.
 *
 * <p>Persists to disk every {@link #PERSIST_EVERY} additions and on JVM
 * shutdown. Restart re-reads the file via {@link InboxVectorStoreConfig} —
 * the agent doesn't lose its understanding of the inbox.
 */
@Component
public class InboxIndexer {

    private static final Logger logger = LoggerFactory.getLogger(InboxIndexer.class);

    private static final int PERSIST_EVERY = 5;

    private final SimpleVectorStore vectorStore;
    private final PreprocessingPipeline pipeline;
    private final ExecutorService embedExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "intellimailbox-indexer");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger sinceLastSave = new AtomicInteger(0);

    public InboxIndexer(@Qualifier("inboxVectorStore") SimpleVectorStore vectorStore,
                        PreprocessingPipeline pipeline) {
        this.vectorStore = vectorStore;
        this.pipeline = pipeline;
    }

    @PostConstruct
    void start() {
        pipeline.subscribe(this::onEvent);
        logger.info("InboxIndexer: subscribed to pipeline events.");
        // Backfill from any enrichments restored from disk on this startup —
        // they were processed BEFORE the indexer existed (or before the
        // embedding model was available), so they're absent from the vector
        // store. Pipeline.subscribe doesn't replay history, so we walk the
        // snapshot ourselves and queue each id. Runs on the single-thread
        // executor so it can't out-race the embedding model and serializes
        // naturally with live events from the pipeline.
        embedExec.submit(this::backfillFromCache);
    }

    private void backfillFromCache() {
        var snap = pipeline.snapshot();
        if (snap.isEmpty()) return;
        logger.info("InboxIndexer: backfilling {} cached entries into the vector store…",
                snap.size());
        int n = 0;
        for (EnrichedEmail e : snap.values()) {
            if (e == null || e.failed()) continue;
            // index() is idempotent (delete-then-add), so re-running on an
            // already-indexed id is harmless. We don't expose a contains()
            // probe on SimpleVectorStore, and the cost is fine: nomic-embed-text
            // on CPU is ~50-150ms per email, so 200 emails ≈ 20 seconds of
            // background work, paid once.
            index(e);
            n++;
        }
        logger.info("InboxIndexer: backfill scheduled {} entries; persisting now.", n);
        // Force a persist after a backfill burst — otherwise the persist
        // counter sits below the threshold and the indexed batch only hits
        // disk on the next shutdown.
        persistNow("backfill");
    }

    @PreDestroy
    void stop() {
        embedExec.shutdown();
        persistNow("shutdown");
    }

    /**
     * Drop every vector currently indexed and persist an empty store.
     * Reading ids from the persisted JSON file (not from
     * {@code pipeline.snapshot()}) ensures we catch orphans — vectors
     * whose corresponding cache entries were removed by an earlier
     * migration drop or by a cache wipe ordered after the indexer add.
     * Without this, {@code /api/cache/clear} would report success while
     * leaving stale embeddings on disk and in memory.
     */
    public int clearVectorStore() {
        // Union of (a) every id the in-memory store could have and (b) any
        // ids only present in the persisted file. Reading the file is the
        // reliable way to enumerate orphans — there's no public list API
        // on SimpleVectorStore.
        java.util.Set<String> allIds = new java.util.LinkedHashSet<>();
        allIds.addAll(pipeline.snapshot().keySet());
        allIds.addAll(readPersistedIds());

        int n = 0;
        for (String id : allIds) {
            try { vectorStore.delete(java.util.List.of(id)); n++; }
            catch (Throwable ignored) { /* not present is fine */ }
        }

        // Belt-and-braces: even if we missed something via reflection /
        // file parsing, removing the persisted file means next startup
        // loads an empty store. The subsequent persistNow() then rewrites
        // an empty file from the in-memory state we just wiped.
        java.io.File f = InboxVectorStoreConfig.VECTOR_FILE.toFile();
        if (f.exists() && !f.delete()) {
            logger.warn("InboxIndexer: couldn't delete {} ({})", f,
                    "filesystem permission issue?");
        }
        sinceLastSave.set(0);
        persistNow("manual-clear");
        logger.info("InboxIndexer: vector store cleared ({} ids dropped).", n);
        return n;
    }

    /** Parse the persisted vector-store JSON to recover every id it holds.
     *  Returns an empty set on first run (no file) or any parse error —
     *  callers union this with the snapshot ids for an exhaustive clear. */
    private java.util.Set<String> readPersistedIds() {
        java.io.File f = InboxVectorStoreConfig.VECTOR_FILE.toFile();
        if (!f.isFile() || f.length() == 0) return java.util.Set.of();
        try {
            // SimpleVectorStore's on-disk shape is a flat
            // { "<id>": { ... }, "<id2>": { ... } } JSON object —
            // the keys are exactly the document ids.
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(f);
            if (root == null || !root.isObject()) return java.util.Set.of();
            java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
            root.fieldNames().forEachRemaining(out::add);
            return out;
        } catch (Exception e) {
            logger.debug("InboxIndexer: couldn't read persisted ids ({})", e.toString());
            return java.util.Set.of();
        }
    }

    private void onEvent(PipelineEvent event) {
        if (!(event instanceof PipelineEvent.Enriched enriched)) return;
        EnrichedEmail email = enriched.email();
        if (email == null || email.failed()) return;
        // Capture the pipeline's cache epoch HERE, on the publish thread,
        // before queueing the embedding task. If /api/cache/clear fires
        // between this capture and the embedExec task actually running,
        // the epoch will have moved on and index() will skip the add —
        // otherwise clearVectorStore() would wipe the store and then this
        // queued task would re-add the just-cleared vectors moments later.
        final long jobEpoch = pipeline.currentEpoch();
        embedExec.submit(() -> {
            if (jobEpoch != pipeline.currentEpoch()) {
                logger.debug("InboxIndexer: dropping embed for id={} — cache cleared mid-queue.",
                        email.item() == null ? "?" : email.item().id());
                return;
            }
            index(email);
        });
    }

    private void index(EnrichedEmail email) {
        try {
            String id = email.item() == null ? null : email.item().id();
            if (id == null || id.isBlank()) return;
            String text = renderForEmbedding(email);
            if (text.isBlank()) return;
            // Re-add (no upsert in SimpleVectorStore): delete then add ensures a
            // re-enriched email replaces the prior doc rather than duplicating.
            try { vectorStore.delete(List.of(id)); } catch (Throwable ignored) { /* nothing to delete is fine */ }
            vectorStore.add(List.of(buildDocument(id, email, text)));
            if (sinceLastSave.incrementAndGet() >= PERSIST_EVERY) {
                sinceLastSave.set(0);
                persistNow("batch");
            }
        } catch (Throwable t) {
            logger.debug("InboxIndexer: skip id={} ({})",
                    email.item() == null ? "?" : email.item().id(), t.toString());
        }
    }

    private Document buildDocument(String id, EnrichedEmail email, String text) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("id", id);
        meta.put("sender", nullSafe(email.item() == null ? "" : email.item().sender()));
        meta.put("subject", nullSafe(email.item() == null ? "" : email.item().subject()));
        meta.put("time", nullSafe(email.item() == null ? "" : email.item().time()));
        List<String> badgeNames = new ArrayList<>();
        if (email.badges() != null) email.badges().forEach(b -> badgeNames.add(b.name()));
        meta.put("badges", String.join(",", badgeNames));
        meta.put("needsReply", email.needsReply());
        return new Document(id, text, meta);
    }

    /**
     * Render one email into the searchable text we embed. Front-loads the
     * fields a user is most likely to query on (subject + sender) so they
     * dominate the embedding even when truncation kicks in deeper down.
     */
    private static String renderForEmbedding(EnrichedEmail email) {
        StringBuilder sb = new StringBuilder(2048);
        if (email.item() != null) {
            if (notBlank(email.item().subject())) sb.append("Subject: ").append(email.item().subject()).append('\n');
            if (notBlank(email.item().sender()))  sb.append("From: ").append(email.item().sender()).append('\n');
            if (notBlank(email.item().time()))    sb.append("When: ").append(email.item().time()).append('\n');
        }
        if (notBlank(email.summary())) sb.append("Summary: ").append(email.summary()).append('\n');
        if (email.ctas() != null && !email.ctas().isEmpty()) {
            sb.append("Actions:\n");
            email.ctas().forEach(c -> sb.append("- ").append(nullSafe(c.text())).append('\n'));
        }
        if (email.item() != null && notBlank(email.item().snippet())) {
            sb.append("Preview: ").append(email.item().snippet()).append('\n');
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String nullSafe(String s) { return s == null ? "" : s; }

    private void persistNow(String reason) {
        try {
            File f = InboxVectorStoreConfig.VECTOR_FILE.toFile();
            Files.createDirectories(f.getParentFile().toPath());
            vectorStore.save(f);
            logger.info("InboxIndexer: persisted vector store ({}).", reason);
        } catch (Throwable t) {
            logger.warn("InboxIndexer: persist failed ({}): {}", reason, t.toString());
        }
    }
}
