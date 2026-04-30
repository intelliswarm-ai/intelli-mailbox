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

    /**
     * {@code synchronized} so concurrent web requests + the worker thread can't
     * leave Chrome in a half-rendered state for the next caller — BrowserTool
     * drives ONE Chrome page, period.
     */
    public synchronized List<InboxItem> listInbox() {
        browser.execute(Map.of("operation", "navigate",
                "url", "https://mail.google.com/mail/u/0/#inbox"));
        browser.execute(Map.of("operation", "wait_for",
                "selector", "[role=\"main\"]",
                "timeout_ms", 30_000));

        // CRITICAL: each row reports its raw DOM index (its position among ALL
        // [role="row"] nodes, header included). The Java side uses that DOM
        // index as the InboxItem id. {@link #readEmail(String)} then clicks
        // rows[id] directly — no listing-vs-DOM offset drift, which previously
        // caused row N to scrape the body of row N+k whenever any earlier row
        // had no sender/subject and got filtered out.
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
                "    if (sender || subject) out.push({domIndex: idx, sender, subject, snippet, time});\n" +
                "  });\n" +
                "  return JSON.stringify(out.slice(0, " + maxEmails + "));\n" +
                "})()";
        Object raw = browser.execute(Map.of("operation", "evaluate_js", "code", js));
        return parseInboxItems(String.valueOf(raw));
    }

    /**
     * Opens the email whose DOM-index id was returned by {@link #listInbox()},
     * scrapes its body, returns to inbox. Synchronized for the same reason as
     * {@code listInbox} — exclusive Chrome-page access during the click + scrape.
     */
    public synchronized String readEmail(String id) {
        int idx;
        try { idx = Integer.parseInt(id); } catch (NumberFormatException e) { return ""; }

        // id IS the raw DOM index — no +1 offset, header was already accounted for
        // when listInbox skipped row 0.
        String clickJs = String.format(
                "(() => { const rows = document.querySelectorAll('[role=\"main\"] [role=\"row\"]'); " +
                "  const target = rows[%d]; if (!target) return false; target.click(); return true; })()",
                idx);
        Object clicked = browser.execute(Map.of("operation", "evaluate_js", "code", clickJs));
        if (!"true".equals(String.valueOf(clicked))) {
            logger.warn("EmailReader: couldn't click row domIndex={}", idx);
            return "";
        }

        browser.execute(Map.of("operation", "wait_for",
                "selector", "[role=\"main\"] [role=\"listitem\"], div.adn",
                "timeout_ms", 15_000));
        // Scrape the MESSAGE container (div.adn), not all of [role="main"] which
        // includes the folder list, search bar, sidebar ads and Gmail UI toasts
        // ("You can't react with an emoji to a group" was leaking through into
        // the LLM input from the latter). Fall back to [role="main"] only if Gmail
        // hasn't materialised div.adn yet — e.g. on a cold view.
        Object body = browser.execute(Map.of("operation", "scrape",
                "selector", "div.adn"));
        String text = String.valueOf(body);
        if (text == null || text.isBlank() || "null".equals(text)) {
            Object fallback = browser.execute(Map.of("operation", "scrape",
                    "selector", "[role=\"main\"]"));
            text = String.valueOf(fallback);
        }

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
            for (Map<String, Object> r : raw) {
                Object dom = r.get("domIndex");
                String id = dom == null ? "" : dom.toString();
                if (id.isBlank()) continue;
                out.add(new InboxItem(
                        id,
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
