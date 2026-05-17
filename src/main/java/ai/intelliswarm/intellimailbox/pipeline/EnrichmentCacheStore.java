package ai.intelliswarm.intellimailbox.pipeline;

import ai.intelliswarm.intellimailbox.enrichment.EnrichedEmail;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Disk persistence for the enrichment cache + raw scraped bodies. Writes to
 * {@code ~/.intelli-mailbox/data/cache.json} on every successful enrichment;
 * loads on JVM start so the user's prior session's work survives restarts.
 *
 * <p>Without this, a restart re-pays the LLM cost for every email the next
 * time the user clicks "Process inbox" — on local Ollama that's 10-30 min
 * for a typical day's inbox. With this, restart picks up immediately, the
 * chat agent retains all the context it built up about the user's inbox,
 * and the in-app diagnostics view doesn't go blank.
 *
 * <p>Writes are debounced + serialized — at most one disk write in flight
 * at a time, with a short coalescing window so a burst of enrichments
 * doesn't trigger a write per row. The file is JSON for the same reason
 * the vector store uses JSON: trivial to inspect with a text editor when
 * something goes wrong, no schema migration story to maintain across
 * versions of the app.
 */
@Component
public class EnrichmentCacheStore {

    private static final Logger logger = LoggerFactory.getLogger(EnrichmentCacheStore.class);

    private static final Path DATA_DIR = Paths.get(System.getProperty("user.home"),
            ".intelli-mailbox", "data");
    private static final Path CACHE_FILE = DATA_DIR.resolve("cache.json");

    private final ObjectMapper json = new ObjectMapper()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Matches the legacy "page:idx" DOM-position keys (e.g. "1:5") used as
     *  cache ids before the stable content-hash id landed. Any cached entry
     *  with such a key is from a schema version that suffered the id-drift
     *  bug and must be discarded on load — keeping it would let drifted
     *  metadata bleed into the new run. */
    private static final java.util.regex.Pattern LEGACY_POSITION_KEY =
            java.util.regex.Pattern.compile("^\\d+:\\d+$|^\\d+$");

    /** Pull cached enrichments off disk. Returns an empty map on first run
     *  or if the file is unreadable / from an incompatible older version.
     *  Legacy "page:idx" keys are silently dropped — see {@link #LEGACY_POSITION_KEY}. */
    public Map<String, EnrichedEmail> load() {
        if (!Files.isRegularFile(CACHE_FILE)) return Map.of();
        try {
            byte[] bytes = Files.readAllBytes(CACHE_FILE);
            if (bytes.length == 0) return Map.of();
            Map<String, EnrichedEmail> loaded = json.readValue(bytes,
                    new TypeReference<Map<String, EnrichedEmail>>() {});
            int before = loaded.size();
            loaded.keySet().removeIf(k -> LEGACY_POSITION_KEY.matcher(k).matches());
            int dropped = before - loaded.size();
            if (dropped > 0) {
                logger.info("EnrichmentCacheStore: dropped {} entries with legacy "
                                + "DOM-position ids — they suffered id-drift across sessions. "
                                + "Re-process the inbox to repopulate.", dropped);
            }
            logger.info("EnrichmentCacheStore: restored {} entries from {}",
                    loaded.size(), CACHE_FILE);
            return loaded;
        } catch (Exception e) {
            // Likely cause: schema drift (e.g. the InboxItem record gained a
            // 'position' field after the existing cache was written). The
            // safe move is to back up the unreadable file and start clean —
            // re-processing rebuilds it, and the backup lets the user
            // forensically inspect if they need to. Without the backup +
            // rename, we'd keep retrying the same failing read on every start.
            logger.warn("EnrichmentCacheStore: couldn't load cache ({}). "
                    + "Backing up to cache.json.bak and starting clean.", e.toString());
            try {
                Path backup = CACHE_FILE.resolveSibling("cache.json.bak");
                Files.move(CACHE_FILE, backup,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception moveEx) {
                logger.debug("EnrichmentCacheStore: backup move failed ({})", moveEx.toString());
            }
            return Map.of();
        }
    }

    /** Same as load() but for raw scraped bodies (separate file because they're
     *  bulky and we sometimes want to flush them without rewriting the enriched cache).
     *  Legacy "page:idx" keys are silently dropped to match {@link #load()}. */
    public Map<String, String> loadBodies() {
        Path file = DATA_DIR.resolve("bodies.json");
        if (!Files.isRegularFile(file)) return Map.of();
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) return Map.of();
            Map<String, String> loaded = json.readValue(bytes, new TypeReference<Map<String, String>>() {});
            loaded.keySet().removeIf(k -> LEGACY_POSITION_KEY.matcher(k).matches());
            return loaded;
        } catch (Exception e) {
            logger.warn("EnrichmentCacheStore: couldn't load bodies cache ({}).", e.toString());
            return Map.of();
        }
    }

    /** Atomic write: serialize to a temp file, then replace the target. Prevents
     *  a half-written file from poisoning the next startup if the JVM dies mid-write. */
    public synchronized void save(Map<String, EnrichedEmail> cache,
                                  Map<String, String> bodies) {
        try {
            Files.createDirectories(DATA_DIR);
            writeAtomically(CACHE_FILE, json.writeValueAsBytes(cache));
            writeAtomically(DATA_DIR.resolve("bodies.json"), json.writeValueAsBytes(bodies));
        } catch (Exception e) {
            logger.warn("EnrichmentCacheStore: save failed ({})", e.toString());
        }
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException amns) {
            // Some Windows FS configurations refuse ATOMIC_MOVE across links —
            // fall back to a plain replace.
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
