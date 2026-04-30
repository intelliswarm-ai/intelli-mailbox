package ai.intelliswarm.intellimailbox.gmail;

import ai.intelliswarm.intellimailbox.pipeline.InboxItem;
import ai.intelliswarm.swarmai.tool.common.BrowserTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around {@link BrowserTool} for the two Gmail interactions this
 * product needs: list the inbox, read one email body.
 *
 * <p>All Gmail-CSS-selector knowledge lives here. If Google rotates their class
 * names, the rest of the app doesn't care.
 */
@Service
public class EmailReader {

    private static final Logger logger = LoggerFactory.getLogger(EmailReader.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BrowserTool browser;
    private final int maxEmails;

    public EmailReader(BrowserTool browser,
                       @org.springframework.beans.factory.annotation.Value(
                               "${intellimailbox.preprocessing.max-emails:25}") int maxEmails) {
        this.browser = browser;
        this.maxEmails = maxEmails;
    }

    public List<InboxItem> listInbox() {
        browser.execute(Map.of("operation", "navigate",
                "url", "https://mail.google.com/mail/u/0/#inbox"));
        browser.execute(Map.of("operation", "wait_for",
                "selector", "[role=\"main\"]",
                "timeout_ms", 30_000));

        // Pull the structured fields with a small focused JS expression — much more
        // reliable than parsing innerText of [role="main"].
        String js = "(() => {\n" +
                "  const rows = document.querySelectorAll('[role=\"main\"] [role=\"row\"]');\n" +
                "  const out = [];\n" +
                "  rows.forEach((r, idx) => {\n" +
                "    if (idx === 0) return;\n" +
                "    const sender  = r.querySelector('[email]')?.getAttribute('name')\n" +
                "                 ?? r.querySelector('[email]')?.getAttribute('email')\n" +
                "                 ?? r.querySelector('span.bA4')?.innerText\n" +
                "                 ?? '';\n" +
                "    const subject = r.querySelector('span.bog')?.innerText ?? '';\n" +
                "    const snippet = r.querySelector('span.y2')?.innerText  ?? '';\n" +
                "    const time    = r.querySelector('span.xW span')?.innerText\n" +
                "                 ?? r.querySelector('td[title]')?.getAttribute('title')\n" +
                "                 ?? '';\n" +
                "    if (sender || subject) out.push({sender, subject, snippet, time});\n" +
                "  });\n" +
                "  return JSON.stringify(out.slice(0, " + maxEmails + "));\n" +
                "})()";
        Object raw = browser.execute(Map.of("operation", "evaluate_js", "code", js));
        return parseInboxItems(String.valueOf(raw));
    }

    /**
     * Opens the email at positional index {@code id}, scrapes its body, returns to inbox.
     * @return the email's visible body text, or empty string if click failed
     */
    public String readEmail(String id) {
        int idx;
        try { idx = Integer.parseInt(id); } catch (NumberFormatException e) { return ""; }

        String clickJs = String.format(
                "(() => { const rows = document.querySelectorAll('[role=\"main\"] [role=\"row\"]'); " +
                "  const target = rows[%d + 1]; if (!target) return false; target.click(); return true; })()",
                idx);
        Object clicked = browser.execute(Map.of("operation", "evaluate_js", "code", clickJs));
        if (!"true".equals(String.valueOf(clicked))) {
            logger.warn("EmailReader: couldn't click row idx={}", idx);
            return "";
        }

        browser.execute(Map.of("operation", "wait_for",
                "selector", "[role=\"main\"] [role=\"listitem\"], div.adn",
                "timeout_ms", 15_000));
        Object body = browser.execute(Map.of("operation", "scrape",
                "selector", "[role=\"main\"]"));
        String text = String.valueOf(body);

        browser.execute(Map.of("operation", "navigate",
                "url", "https://mail.google.com/mail/u/0/#inbox"));
        browser.execute(Map.of("operation", "wait_for",
                "selector", "[role=\"main\"]",
                "timeout_ms", 15_000));

        return text;
    }

    private List<InboxItem> parseInboxItems(String json) {
        if (json == null || json.isBlank() || json.equals("null")) return List.of();
        try {
            String body = json.startsWith("\"") ? JSON.readValue(json, String.class) : json;
            List<Map<String, Object>> raw = JSON.readValue(body, new TypeReference<>() {});
            List<InboxItem> out = new ArrayList<>(raw.size());
            for (int i = 0; i < raw.size(); i++) {
                Map<String, Object> r = raw.get(i);
                out.add(new InboxItem(
                        String.valueOf(i),
                        str(r.get("sender")),
                        str(r.get("subject")),
                        str(r.get("snippet")),
                        str(r.get("time"))));
            }
            return out;
        } catch (Exception e) {
            logger.warn("EmailReader: failed to parse inbox JSON ({}). Raw was: {}",
                    e.getMessage(), excerpt(json, 200));
            return List.of();
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString().trim(); }

    private static String excerpt(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
