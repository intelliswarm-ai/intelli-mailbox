package ai.intelliswarm.intellimailbox.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory cache + write-through adapter on top of {@link ExcludedSendersStore}.
 * Hot path is {@link #isExcluded(String)} called once per inbox row during
 * refresh, so we keep a normalized {@link Set} in memory and only touch disk
 * on add / remove.
 */
@Component
public class ExcludedSendersService {

    private static final Logger logger = LoggerFactory.getLogger(ExcludedSendersService.class);

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final LinkedHashSet<String> displayList = new LinkedHashSet<>();
    private final LinkedHashSet<String> normalizedSet = new LinkedHashSet<>();

    public ExcludedSendersService() {
        reload();
    }

    private void reload() {
        lock.writeLock().lock();
        try {
            displayList.clear();
            normalizedSet.clear();
            for (String s : ExcludedSendersStore.load()) {
                if (s == null) continue;
                displayList.add(s);
                normalizedSet.add(ExcludedSendersStore.normalize(s));
            }
            logger.info("Loaded {} excluded senders from {}", displayList.size(), ExcludedSendersStore.path());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<String> list() {
        lock.readLock().lock();
        try {
            return List.copyOf(displayList);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Adds {@code sender} to the list. Returns {@code true} if newly added,
     * {@code false} if it was already there. Empty / null input is silently
     * ignored (returns {@code false}).
     */
    public boolean add(String sender) {
        if (sender == null) return false;
        String trimmed = sender.trim();
        if (trimmed.isEmpty()) return false;
        String norm = ExcludedSendersStore.normalize(trimmed);

        lock.writeLock().lock();
        try {
            if (!normalizedSet.add(norm)) return false;
            displayList.add(trimmed);
            persist();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes any entry that normalizes to {@code sender}. Returns {@code true}
     * if a removal happened.
     */
    public boolean remove(String sender) {
        if (sender == null) return false;
        String norm = ExcludedSendersStore.normalize(sender);
        if (norm.isEmpty()) return false;

        lock.writeLock().lock();
        try {
            if (!normalizedSet.remove(norm)) return false;
            displayList.removeIf(s -> ExcludedSendersStore.normalize(s).equals(norm));
            persist();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Hot path during inbox refresh — case-insensitive contains check. */
    public boolean isExcluded(String sender) {
        if (sender == null) return false;
        String norm = ExcludedSendersStore.normalize(sender);
        if (norm.isEmpty()) return false;
        lock.readLock().lock();
        try {
            return normalizedSet.contains(norm);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void persist() {
        // Caller already holds writeLock.
        try {
            ExcludedSendersStore.save(Set.copyOf(displayList));
        } catch (IOException e) {
            // We intentionally don't roll back the in-memory change on disk
            // failure — the user's intent is honored for this session, and
            // they'll get a warning in the log so the next launch can see why
            // the file is stale.
            logger.warn("Couldn't persist excluded senders to {}: {}", ExcludedSendersStore.path(), e.toString());
        }
    }
}
