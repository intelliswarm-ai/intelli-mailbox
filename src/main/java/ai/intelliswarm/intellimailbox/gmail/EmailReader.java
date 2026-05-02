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
     *
     * <p>Hard-navigates to {@code mail.google.com} only when we have to.
     * BrowserTool's navigate calls Playwright's {@code waitForLoadState(NETWORKIDLE)}
     * which, on Gmail, waits the full 30s default before timing out (Gmail's
     * persistent long-poll connections never let network activity drop to zero).
     * So skip the navigate when the listing's row nodes are already in the DOM,
     * and use a hash change as a fallback when we're on Gmail but not on the
     * listing — that re-routes the SPA without a full reload.
     */
    public synchronized List<InboxItem> listInbox() {
        ensureOnInboxListing(30_000);
        // ensureOnInboxListing only no-ops when the listing is already in the
        // DOM — it does NOT tell Gmail to fetch fresh state from the server.
        // Without this extra step, "Refresh inbox" would just re-scrape the
        // same DOM Gmail had at page load and would never see emails that
        // arrived since (e.g. one the user just sent to themselves). Click
        // Gmail's own Refresh button to force a real re-fetch.
        forceGmailRefetch();

        // CRITICAL: each row reports its raw DOM index (its position among ALL
        // [role="row"] nodes, header included). The Java side uses that DOM
        // index as the InboxItem id. {@link #readEmail(String)} then clicks
        // rows[id] directly — no listing-vs-DOM offset drift, which previously
        // caused row N to scrape the body of row N+k whenever any earlier row
        // had no sender/subject and got filtered out.
        // Snippet cleanup: Gmail prepends a separator dash to its preview
        // ("- Hello, ..."). Strip leading dashes / em-dashes / en-dashes so
        // the snippet reads naturally and the LLM doesn't see leading garbage.
        // Time fallbacks: Gmail's row time uses 4 different DOM patterns
        // depending on locale + column-width — try them all.
        String js = "(() => {\n" +
                "  const cleanSnippet = s => (s || '').replace(/^\\s*[-–—·•]+\\s*/, '').trim();\n" +
                "  const rows = document.querySelectorAll('[role=\"main\"] [role=\"row\"]');\n" +
                "  const out = [];\n" +
                "  rows.forEach((r, idx) => {\n" +
                "    if (idx === 0) return;\n" +
                "    const sender  = r.querySelector('[email]')?.getAttribute('name')\n" +
                "                 ?? r.querySelector('[email]')?.getAttribute('email')\n" +
                "                 ?? r.querySelector('span.bA4')?.innerText\n" +
                "                 ?? '';\n" +
                "    const subject = r.querySelector('span.bog')?.innerText ?? '';\n" +
                "    const snippetRaw = r.querySelector('span.y2')?.innerText\n" +
                "                    ?? r.querySelector('span.bog + span')?.innerText\n" +
                "                    ?? '';\n" +
                "    const snippet = cleanSnippet(snippetRaw);\n" +
                "    // Gmail's relative time: try (1) compact column span (.xW span),\n" +
                "    // (2) any <span title> with a digit in title (full date in tooltip),\n" +
                "    // (3) td[title], (4) any element with class xY/xX (older Gmail).\n" +
                "    const time    = r.querySelector('span.xW span')?.innerText\n" +
                "                 ?? r.querySelector('span.xW')?.innerText\n" +
                "                 ?? r.querySelector('span[title]:not([email])')?.getAttribute('title')\n" +
                "                 ?? r.querySelector('td[title]')?.getAttribute('title')\n" +
                "                 ?? r.querySelector('span.xY span')?.innerText\n" +
                "                 ?? '';\n" +
                "    if (sender || subject) out.push({domIndex: idx, sender, subject, snippet, time: (time || '').trim()});\n" +
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

        // Defensive: make sure we're on the inbox listing before clicking
        // rows[idx]. Cheap when we already are (single probe call); recovers
        // when a previous readEmail's hash-nav-back didn't render fast enough.
        ensureOnInboxListing(15_000);

        // CRITICAL — stale-DOM avoidance. Gmail's SPA caches previously-opened
        // conversation panels in the DOM (their {@code div.adn} stays after we
        // hash-nav back to the listing). If we just queried 'div.adn' next, we'd
        // pull the PREVIOUS email's body. So before the click, tag every
        // existing div.adn as 'stale', then later scrape only the fresh one.
        // This is what the old hard navigate() got us for free (full DOM reset);
        // we now do it explicitly to keep the navigate-skip optimisation safe.
        browser.execute(Map.of("operation", "evaluate_js",
                "code", "(() => { document.querySelectorAll('div.adn').forEach(e => e.setAttribute('data-imb-stale','1')); return true; })()"));

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

        // Poll for a FRESH div.adn (one without the stale marker we just set).
        // Old wait_for "div.adn" returned instantly off the cached element from
        // the previous read — that's how the body/index mismatch ("AEGEAN row →
        // InfoQ analysis") happened. We now positively confirm a new element
        // landed before scraping.
        String pollJs = "(() => { return Array.from(document.querySelectorAll('div.adn'))"
                + ".some(e => !e.hasAttribute('data-imb-stale')) ? 'ok' : 'pending'; })()";
        long deadline = System.currentTimeMillis() + 15_000;
        boolean appeared = false;
        while (System.currentTimeMillis() < deadline) {
            Object res = browser.execute(Map.of("operation", "evaluate_js", "code", pollJs));
            if ("ok".equals(String.valueOf(res))) { appeared = true; break; }
            try { Thread.sleep(150); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!appeared) {
            logger.warn("EmailReader: fresh email body didn't appear for row {} (Gmail SPA may not have rendered the click target)", idx);
            return "";
        }

        // Scrape the FRESH email body. We deliberately target Gmail's
        // {@code div.a3s} — the actual rendered message body, NOT the outer
        // div.adn wrapper which also contains the email's toolbar ("Reply",
        // "More", "Add reaction"), the "to me" recipient line, and the
        // message-header timestamp chrome. Scraping div.adn directly was
        // leaking all of that into the LLM prompt and into the Debug view.
        //
        // Fallback chain: div.a3s.aiL → div.a3s → div.ii.gt → div.ii.
        // If none match (rare — promo/marketing emails sometimes), we clone
        // the wrapper, prune known chrome subtrees, then extract innerText.
        String scrapeJs = "(() => {"
                + "  const fresh = Array.from(document.querySelectorAll('div.adn'))"
                + "      .filter(e => !e.hasAttribute('data-imb-stale'));"
                + "  if (fresh.length === 0) return '';"
                + "  const wrapper = fresh[fresh.length - 1];"
                + "  const body = wrapper.querySelector('div.a3s.aiL')"
                + "             || wrapper.querySelector('div.a3s')"
                + "             || wrapper.querySelector('div.ii.gt')"
                + "             || wrapper.querySelector('div.ii');"
                + "  if (body) return body.innerText || '';"
                + "  /* Pruned-wrapper fallback. Clone, drop the toolbar / */"
                + "  /* header / clipped-affordance subtrees, then extract. */"
                + "  const clone = wrapper.cloneNode(true);"
                + "  const pruneSels = ["
                + "    'div.gE.iv',           /* message-header strip */"
                + "    'div.ade',             /* attachment / quick-action bar */"
                + "    'div.acX',             /* From-line + Unsubscribe row */"
                + "    'div.ad8',             /* recipient / to-me row */"
                + "    'div.adL',             /* clipped-message affordance */"
                + "    '[role=\"toolbar\"]',  /* explicit toolbar */"
                + "    'span[aria-label*=\"Reply\"]',"
                + "    'div[aria-label*=\"Reply\"]'"
                + "  ];"
                + "  pruneSels.forEach(s => clone.querySelectorAll(s).forEach(el => el.remove()));"
                + "  return clone.innerText || '';"
                + "})()";
        Object body = browser.execute(Map.of("operation", "evaluate_js", "code", scrapeJs));
        String text = String.valueOf(body);
        if (text == null || text.isBlank() || "null".equals(text)) {
            // Last-ditch fallback: full main pane scrape. Only triggers if the
            // fresh-div.adn pick returned empty, which usually means Gmail used
            // a different DOM structure for this row (rare, e.g. promo emails).
            Object fallback = browser.execute(Map.of("operation", "scrape",
                    "selector", "[role=\"main\"]"));
            text = String.valueOf(fallback);
        }
        // Belt-and-braces post-clean. Even when we hit div.a3s, Gmail
        // sometimes injects translation prompts, "+1 more", or stray
        // toolbar text via overlays. Drop the well-known chrome lines.
        text = stripGmailChrome(text);

        // Hash-only "navigate back" to the listing. Setting location.hash makes
        // Gmail's SPA re-render the inbox without firing a full page reload, so
        // we skip the 30s NETWORKIDLE wait that a hard navigate would incur.
        // The next readEmail can find rows[idx] without another minute of Playwright
        // sitting on its hands.
        browser.execute(Map.of("operation", "evaluate_js",
                "code", "(() => { try { if (location.hash !== '#inbox') location.hash = '#inbox'; return true; } catch (e) { return false; } })()"));
        try {
            browser.execute(Map.of("operation", "wait_for",
                    "selector", "[role=\"main\"] [role=\"row\"]",
                    "timeout_ms", 15_000));
        } catch (Exception ignored) {
            // If rows didn't reappear in 15s, the next listInbox() call's
            // ensureOnInboxListing will recover (it'll fall back to a hard navigate).
        }

        return text;
    }

    /**
     * Cheapest path to "Gmail's inbox listing is currently rendered". Tries in
     * order: (1) detect listing already in DOM → no-op; (2) detect we're on
     * Gmail but in some other view → set the URL hash and let the SPA re-route
     * (no network reload); (3) hard navigate.
     *
     * <p>On a freshly-launched Chrome that's already on Gmail (the run.sh
     * pre-warm path), case (1) hits and we save the full 30s navigate timeout.
     */
    /**
     * Tell Gmail to re-fetch the inbox from the server. Tries Gmail's own
     * "Refresh" button first (a circular-arrow icon in the toolbar with
     * {@code aria-label="Refresh"} — internationalised, so we match
     * case-insensitively on substrings like "refresh" / "actualizar" /
     * "rafraîchir"). If we can't find that button (Gmail rotates DOM
     * occasionally), we fall back to a hash-toggle: jump to a junk hash
     * and back to {@code #inbox}, which re-routes Gmail's SPA and forces
     * it to re-render the listing with fresh data.
     *
     * <p>After triggering refresh, we re-wait for the row nodes — they
     * briefly disappear during refresh and the next caller's evaluate_js
     * would see an empty listing.
     */
    private void forceGmailRefetch() {
        String forceJs = "(() => {"
                + "  const sels = ["
                + "    'div[role=\"button\"][aria-label=\"Refresh\"]',"
                + "    'div[role=\"button\"][aria-label=\"refresh\"]'"
                + "  ];"
                + "  for (const s of sels) { const b = document.querySelector(s);"
                + "    if (b) { b.click(); return 'clicked-' + s; } }"
                + "  const all = document.querySelectorAll('div[role=\"button\"][aria-label]');"
                + "  for (const b of all) {"
                + "    const al = (b.getAttribute('aria-label') || '').toLowerCase();"
                + "    if (al.includes('refresh') || al.includes('reload')"
                + "      || al.includes('actualizar') || al.includes('aktualisieren')"
                + "      || al.includes('rafraîchir') || al.includes('aggiornare')) {"
                + "      b.click(); return 'clicked-i18n:' + al; } }"
                + "  const cur = location.hash || '#inbox';"
                + "  location.hash = '#search/' + Date.now();"
                + "  setTimeout(() => { location.hash = cur; }, 30);"
                + "  return 'hash-toggled';"
                + "})()";
        try {
            Object result = browser.execute(Map.of("operation", "evaluate_js", "code", forceJs));
            logger.debug("forceGmailRefetch: {}", result);
        } catch (Exception e) {
            logger.debug("forceGmailRefetch failed (continuing with stale DOM): {}", e.toString());
            return;
        }
        // Gmail tears down + rebuilds the row nodes during refresh. Wait up
        // to 8s for the rebuilt rows to land before scraping.
        try {
            browser.execute(Map.of("operation", "wait_for",
                    "selector", "[role=\"main\"] [role=\"row\"]",
                    "timeout_ms", 8_000));
        } catch (Exception e) {
            logger.debug("forceGmailRefetch: rows didn't reappear in 8s — scraping anyway");
        }
    }

    private void ensureOnInboxListing(int waitMs) {
        // Combined probe: returns one of "ready" (already on listing),
        // "hashed" (was on Gmail, switched hash), "navigate" (need a hard nav).
        // Single evaluate_js call so we don't pay round-trips for the diagnosis.
        String probeJs = "(() => { try {"
                + "  if (document.querySelector('[role=\"main\"] [role=\"row\"]')) return 'ready';"
                + "  if (location.hostname.indexOf('mail.google.com') >= 0) {"
                + "    if (location.hash !== '#inbox') location.hash = '#inbox';"
                + "    return 'hashed';"
                + "  }"
                + "  return 'navigate';"
                + "} catch (e) { return 'navigate'; } })()";
        String mode;
        try {
            mode = String.valueOf(browser.execute(Map.of("operation", "evaluate_js", "code", probeJs)));
        } catch (Exception e) {
            mode = "navigate";
        }
        if ("navigate".equals(mode)) {
            browser.execute(Map.of("operation", "navigate",
                    "url", "https://mail.google.com/mail/u/0/#inbox"));
        }
        if (!"ready".equals(mode)) {
            browser.execute(Map.of("operation", "wait_for",
                    "selector", "[role=\"main\"] [role=\"row\"]",
                    "timeout_ms", waitMs));
        }
    }

    /**
     * Opens the email at {@code id}, clicks Gmail's inline Reply button,
     * and pastes {@code draftText} into the compose textbox. Stops there —
     * never auto-sends. The user reviews and clicks Send themselves in
     * Chrome.
     *
     * <p>Returns false (and leaves Chrome wherever it ended up) if any of
     * the three Gmail selectors (row, Reply button, compose textbox) can't
     * be located in time. The frontend surfaces this as a per-button error
     * state and the user falls back to Copy.
     *
     * <p>Synchronized for the same reason as {@link #listInbox()} and
     * {@link #readEmail(String)} — BrowserTool drives one Chrome page; the
     * web thread calling this must not race against the enrichment worker.
     */
    public synchronized boolean injectReply(String id, String draftText) {
        try {
            return injectReplyInner(id, draftText);
        } catch (Throwable t) {
            // Any unexpected throw (BrowserTool internals, OOM, etc.) collapses
            // to a clean false — the controller surfaces it to the user as
            // "Gmail DOM didn't respond …" instead of a 500.
            logger.warn("EmailReader.injectReply: unhandled error for id={} ({})",
                    id, t.toString());
            return false;
        }
    }

    private boolean injectReplyInner(String id, String draftText) {
        int idx;
        try { idx = Integer.parseInt(id); } catch (NumberFormatException e) { return false; }
        if (draftText == null || draftText.isBlank()) return false;

        // 1. Click the row to open the email — same DOM-index trick as readEmail.
        String clickJs = String.format(
                "(() => { const rows = document.querySelectorAll('[role=\"main\"] [role=\"row\"]'); " +
                "  const target = rows[%d]; if (!target) return false; target.click(); return true; })()",
                idx);
        Object clicked = browser.execute(Map.of("operation", "evaluate_js", "code", clickJs));
        if (!"true".equals(String.valueOf(clicked))) {
            logger.warn("EmailReader.injectReply: couldn't click row {}", idx);
            return false;
        }
        try {
            browser.execute(Map.of("operation", "wait_for",
                    "selector", "div.adn, [role=\"main\"] [role=\"listitem\"]",
                    "timeout_ms", 15_000));
        } catch (Exception e) {
            logger.warn("EmailReader.injectReply: email body didn't render for row {} ({})",
                    idx, e.getMessage());
            return false;
        }

        // 2. Click the inline Reply button. Gmail rotates selectors, so we try
        //    a small allow-list of variations seen in the wild. We deliberately
        //    target the bottom-of-thread inline reply (not "Reply all", not the
        //    overflow menu reply).
        String replyJs =
                "(() => {\n" +
                "  const sels = [\n" +
                "    'div[role=\"button\"][aria-label^=\"Reply\"][aria-label$=\"message\"]',\n" +
                "    'div[role=\"button\"][aria-label=\"Reply\"]',\n" +
                "    'span[role=\"link\"][aria-label^=\"Reply\"]',\n" +
                "    'div.amn div[role=\"button\"]:not([aria-label*=\"all\"]):not([aria-label*=\"forward\" i])'\n" +
                "  ];\n" +
                "  for (const sel of sels) {\n" +
                "    const btn = document.querySelector(sel);\n" +
                "    if (btn) { btn.click(); return true; }\n" +
                "  }\n" +
                "  return false;\n" +
                "})()";
        Object replyClicked = browser.execute(Map.of("operation", "evaluate_js", "code", replyJs));
        if (!"true".equals(String.valueOf(replyClicked))) {
            logger.warn("EmailReader.injectReply: couldn't find Reply button for row {}", idx);
            return false;
        }

        // 3. Wait for the compose textbox to materialise.
        try {
            browser.execute(Map.of("operation", "wait_for",
                    "selector", "div[role=\"textbox\"][aria-label*=\"Message Body\"], div[g_editable=\"true\"][role=\"textbox\"]",
                    "timeout_ms", 15_000));
        } catch (Exception e) {
            logger.warn("EmailReader.injectReply: compose textbox didn't render ({})", e.getMessage());
            return false;
        }

        // 4. Inject the draft. Use Jackson to JSON-encode the text — handles
        //    quotes, newlines, unicode without bespoke escaping. We replace
        //    \n with <br> for the contenteditable, and HTML-escape angle
        //    brackets so a draft containing literal "<" doesn't smuggle markup
        //    into Gmail's compose DOM.
        String jsLiteral;
        try {
            jsLiteral = JSON.writeValueAsString(draftText);
        } catch (Exception e) {
            return false;
        }
        String injectJs = """
                (() => {
                  const text = %s;
                  const editor = document.querySelector('div[role="textbox"][aria-label*="Message Body"]')
                              || document.querySelector('div[g_editable="true"][role="textbox"]');
                  if (!editor) return false;
                  editor.focus();
                  const html = text
                      .split('\\n')
                      .map(l => l.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'))
                      .join('<br>');
                  editor.innerHTML = html;
                  // Move caret to the end so the user types at the end if they edit.
                  const range = document.createRange();
                  range.selectNodeContents(editor);
                  range.collapse(false);
                  const sel = window.getSelection();
                  sel.removeAllRanges();
                  sel.addRange(range);
                  // Trigger Gmail's draft-saver.
                  editor.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText' }));
                  return true;
                })()
                """.formatted(jsLiteral);
        Object injected = browser.execute(Map.of("operation", "evaluate_js", "code", injectJs));
        boolean ok = "true".equals(String.valueOf(injected));
        if (!ok) {
            logger.warn("EmailReader.injectReply: text injection failed for row {}", idx);
        }
        // Deliberately DO NOT navigate back to the inbox here — the user needs
        // Chrome to stay on the open compose so they can review and Send.
        return ok;
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

    /**
     * Drop residual Gmail UI chrome that occasionally bleeds into the message
     * body even when we scrape {@code div.a3s} directly. Run line-by-line so
     * we don't accidentally chop a real message line that happens to contain
     * one of these substrings — only an exact-trimmed-line match is dropped.
     *
     * <p>The list is intentionally conservative: anything ambiguous (e.g.
     * "Add to calendar" — could be a real CTA, not just chrome) stays. The
     * goal is "remove obvious noise", not "remove everything that smells UI".
     */
    static String stripGmailChrome(String body) {
        if (body == null || body.isBlank()) return body;

        // First pass: strip Gmail's preheader-padding characters. These are
        // U+034F COMBINING GRAPHEME JOINER + zero-width spaces marketing
        // emails inject so the preview text looks padded — pure noise to a
        // language model and visually ugly in the Debug view.
        String cleaned = body
                .replace("͏", "")  // combining grapheme joiner
                .replace("​", "")  // zero-width space
                .replace("‌", "")  // zero-width non-joiner
                .replace("‍", "")  // zero-width joiner
                .replace("﻿", ""); // zero-width no-break space (BOM)

        // Lines that are 100% Gmail-viewer UI. Whole-trimmed-line match only —
        // we never drop a real message line that happens to mention "Reply".
        java.util.Set<String> drops = java.util.Set.of(
                "Add reaction",
                "Reply",
                "Reply all",
                "Forward",
                "More",
                "to me",
                "to me,",
                "to me, others",
                "Show original",
                "Show trimmed content",
                "Translate message",
                "Translate to English",
                "Turn off for: French",
                "Turn off for: German",
                "Turn off for: Spanish",
                "Turn off for: Italian",
                "Turn off for: Portuguese",
                "Turn off for: Greek",
                "Turn off for: Dutch",
                "Turn off for: Polish",
                "View this email in your browser",
                "Unsubscribe",
                "Mark as not important",
                // Snippet-level chrome that LinkedIn / Substack / etc. trip:
                "You can't react with an emoji to a group",
                "[Message clipped]",
                "View entire message",
                // The Gmail-clipped affordance sometimes emits both halves:
                "[Message clipped]  View entire message");

        String[] lines = cleaned.split("\\r?\\n");
        StringBuilder out = new StringBuilder(cleaned.length());
        boolean prevBlank = false;
        for (String raw : lines) {
            String trimmed = raw.trim();
            if (drops.contains(trimmed)) continue;

            // Pattern: Gmail's relative-time stamp line — "2:38 PM (29 minutes ago)".
            if (trimmed.matches("(?i)^\\d{1,2}:\\d{2}\\s*(AM|PM)?\\s*\\([^)]+\\s+ago\\)$")) continue;

            // Pattern: the email's "From" header line that Gmail injects ABOVE
            // the message body — typically combines sender + email + Unsubscribe:
            // "LinkedIn Job Alerts <jobalerts-noreply@linkedin.com> Unsubscribe".
            // Heuristic: contains an email-in-angle-brackets AND ends with
            // "Unsubscribe" — never appears in real email body text.
            if (trimmed.endsWith("Unsubscribe")
                    && trimmed.matches(".*<[^@\\s]+@[^>\\s]+>.*Unsubscribe$")) continue;

            // Pattern: clipped-message footer that Gmail attaches when the email
            // exceeds 102 KB — "...    [Message clipped]  View entire message".
            if (trimmed.matches("^\\.\\.\\.\\s*\\[Message clipped\\].*View entire message.*$")) continue;
            // And the bare "..." marker that Gmail sometimes emits on its own line.
            if (trimmed.equals("...") || trimmed.equals("…")) continue;

            // Collapse runs of blank lines to a single blank (after a non-blank).
            if (trimmed.isEmpty()) {
                if (prevBlank) continue;
                prevBlank = true;
            } else {
                prevBlank = false;
            }
            out.append(raw).append('\n');
        }
        return out.toString().trim();
    }
}
