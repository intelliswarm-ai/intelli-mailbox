package ai.intelliswarm.intellimailbox.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Chat with your inbox" endpoint. Single round-trip per request:
 *
 * <pre>
 *   POST /api/chat
 *   { "message": "what did Sarah email me about Q3?" }
 *   → { ok: true, answer: "...", citations: [ {id, sender, subject}, ... ] }
 * </pre>
 *
 * <p>Non-streaming for Phase 1 — the agent's tool-calling roundtrips work
 * cleanly as a single blocking call. Streaming is a follow-up once the tool
 * UI on the frontend is in place.
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(path = "/chat",
                 consumes = { MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE })
    public ResponseEntity<Map<String, Object>> chat(
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> out = new HashMap<>();
        try {
            String message = null;
            if (body != null && body.get("message") instanceof String s) {
                message = s.trim();
            }
            logger.info("/api/chat ← {} chars", message == null ? 0 : message.length());
            ChatService.ChatAnswer answer = chatService.ask(message == null ? "" : message);
            out.put("ok", true);
            out.put("answer", answer.text());
            // Map record list to plain LinkedHashMap entries so the JSON shape
            // matches the frontend type without depending on Jackson's record
            // serialization config.
            List<Map<String, Object>> cites = answer.citations().stream()
                    .map(c -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("id", c.id());
                        m.put("sender", c.sender());
                        m.put("subject", c.subject());
                        return m;
                    })
                    .toList();
            out.put("citations", cites);
            return ResponseEntity.ok(out);
        } catch (Throwable t) {
            logger.warn("/api/chat unhandled: {}", t.toString(), t);
            out.put("ok", false);
            out.put("answer", "");
            out.put("citations", List.of());
            out.put("error", t.getClass().getSimpleName() + ": "
                    + (t.getMessage() == null ? "(no message)" : t.getMessage()));
            return ResponseEntity.ok(out);
        }
    }
}
