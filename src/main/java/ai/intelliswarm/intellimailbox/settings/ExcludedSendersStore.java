package ai.intelliswarm.intellimailbox.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Persists the user's "do not process" sender list as JSON next to
 * {@code settings.json}. Right-clicking a row in the inbox view and choosing
 * "Exclude sender from processing" appends the row's displayed sender to this
 * list; the next refresh hides those rows entirely (option B in the original
 * design conversation).
 *
 * <p>Storage shape — one flat array of strings, one entry per excluded sender:
 * <pre>
 * { "senders": ["Acme Newsletter", "no-reply@example.com"] }
 * </pre>
 *
 * <p>Comparison is case-insensitive and trim-tolerant ({@code  Acme  } matches
 * {@code acme}). We deliberately match the raw {@code InboxItem.sender} string
 * (which can be a display name, an email, or both) rather than only an email
 * address — Gmail's listing surfaces whichever the user already sees, so the
 * "I right-clicked this row, exclude the thing I saw" mental model holds.
 */
public final class ExcludedSendersStore {

    private static final Logger logger = LoggerFactory.getLogger(ExcludedSendersStore.class);

    private static final Path PATH = Paths.get(
            System.getProperty("user.home"), ".intelliswarm", "intellimailbox", "excluded-senders.json");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private ExcludedSendersStore() { }

    public static Path path() { return PATH; }

    /**
     * Returns the persisted set, preserving insertion order. Entries are stored
     * verbatim (original case + spacing); callers normalize at compare-time via
     * {@link #normalize(String)}.
     */
    public static LinkedHashSet<String> load() {
        if (!Files.exists(PATH)) return new LinkedHashSet<>();
        try {
            Wrapper w = MAPPER.readValue(PATH.toFile(), Wrapper.class);
            if (w == null || w.senders == null) return new LinkedHashSet<>();
            return new LinkedHashSet<>(w.senders);
        } catch (IOException e) {
            logger.warn("Failed to read {}: {} — starting with empty list", PATH, e.toString());
            return new LinkedHashSet<>();
        }
    }

    public static void save(Set<String> senders) throws IOException {
        Files.createDirectories(PATH.getParent());
        Wrapper w = new Wrapper();
        w.senders = new LinkedHashSet<>(senders);
        MAPPER.writeValue(PATH.toFile(), w);
    }

    /** Trim + lowercase, null-safe; returns "" for null/blank input. */
    public static String normalize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase();
    }

    /** Jackson wire format. */
    public static final class Wrapper {
        public LinkedHashSet<String> senders;
    }
}
