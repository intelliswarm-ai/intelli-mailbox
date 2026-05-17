package ai.intelliswarm.intellimailbox.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Wires the in-memory vector store that powers "chat with your inbox" (RAG).
 *
 * <p>SimpleVectorStore keeps embeddings in a ConcurrentHashMap and persists them
 * to a single JSON file. Good enough for the inbox corpus (low hundreds → low
 * thousands of emails) and adds zero infrastructure dependencies — no Chroma,
 * no pgvector. The persisted file lives under {@code ~/.intelli-mailbox/data/}
 * alongside other user-state files; on startup we load it so chat works
 * immediately without re-embedding everything.
 *
 * <p>Bean name is deliberately {@code inboxVectorStore} (NOT the default
 * {@code vectorStore}) — swarmai-tools transitively pulls the Chroma + PgVector
 * starters which each register a {@code vectorStore} bean. The Ollama profile
 * excludes their auto-configs to avoid BeanDefinitionOverrideException; using
 * a distinct name here means the conflict can never resurface regardless of
 * which exclusions are active.
 */
@Configuration
public class InboxVectorStoreConfig {

    private static final Logger logger = LoggerFactory.getLogger(InboxVectorStoreConfig.class);

    static final Path DATA_DIR = Paths.get(System.getProperty("user.home"),
            ".intelli-mailbox", "data");
    static final Path VECTOR_FILE = DATA_DIR.resolve("inbox-vectors.json");

    @Bean(name = "inboxVectorStore")
    public SimpleVectorStore inboxVectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File f = VECTOR_FILE.toFile();
        if (f.isFile() && f.length() > 0) {
            try {
                store.load(f);
                logger.info("InboxVectorStore: loaded persisted index from {} ({} bytes)",
                        f, f.length());
            } catch (Exception e) {
                logger.warn("InboxVectorStore: couldn't load persisted index — starting empty. ({})",
                        e.toString());
            }
        } else {
            logger.info("InboxVectorStore: no persisted index yet — will create on first add.");
        }
        return store;
    }
}
