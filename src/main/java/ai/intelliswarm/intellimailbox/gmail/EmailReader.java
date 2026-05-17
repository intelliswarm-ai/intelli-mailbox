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
    private final int maxEmailsRanged;

    /**
     * The most recent range query passed to {@link #listInbox(String)}. Used
     * by {@link #readEmail(String)} when the email's id encodes a search
     * page (format {@code "<page>:<idx>"}) — we re-navigate to that exact
     * search/page in Gmail before clicking the row.
     */
    private volatile String lastRangeQuery = null;

    public EmailReader(BrowserTool browser,
                       @org.springframework.beans.factory.annotation.Value(
                               "${intellimailbox.preprocessing.max-emails:25}") int maxEmails,
                       @org.springframework.beans.factory.annotation.Value(
                               "${intellimailbox.preprocessing.max-emails-ranged:1000}") int maxEmailsRanged) {
        this.browser = browser;
        this.maxEmails = maxEmails;
        this.maxEmailsRanged = maxEmailsRanged;
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
        return listInbox(null);
    }

    /**
     * Date-range scoped listing. Pass a Gmail {@code newer_than:} duration
     * like {@code "1d"}, {@code "2d"}, {@code "7d"}, {@code "14d"},
     * {@code "30d"} to fetch every inbox email within that window — the
     * reader navigates Gmail's SPA to the corresponding search URL
     * ({@code in:inbox newer_than:<range>}) so we get older emails the
     * default inbox listing would have scrolled off.
     *
     * <p>{@code null} or empty range falls back to the default behaviour
     * (current inbox listing, refreshed via Gmail's own Refresh button).
     *
     * <p>Result is still capped at {@link #maxEmails} per the
     * {@code intellimailbox.preprocessing.max-emails} property — bump it
     * if you want bigger windows.
     */
    public synchronized List<InboxItem> listInbox(String range) {
        boolean ranged = range != null && !range.isBlank() && range.matches("\\d+[dwm]");
        if (!ranged) {
            ensureOnInboxListing(30_000);
            // ensureOnInboxListing only no-ops when the listing is already in the
            // DOM — it does NOT tell Gmail to fetch fresh state from the server.
            // Without this extra step, "Refresh inbox" would just re-scrape the
            // same DOM Gmail had at page load and would never see emails that
            // arrived since (e.g. one the user just sent to themselves). Click
            // Gmail's own Refresh button to force a real re-fetch.
            forceGmailRefetch();
            this.lastRangeQuery = null;
            return scrapeRowsOnPage(1, maxEmails);
        }

        // Ranged query — paginate Gmail's search results until we hit the cap
        // or run out of pages. Each emitted InboxItem.id encodes the page it
        // came from ("<page>:<domIdx>") so readEmail can navigate back to the
        // exact page before clicking the row.
        String r = range.trim().toLowerCase();
        this.lastRangeQuery = r;
        navigateToRangeSearch(r, 1);
        java.util.List<InboxItem> all = new java.util.ArrayList<>();
        int page = 1;
        while (all.size() < maxEmailsRanged) {
            int remaining = maxEmailsRanged - all.size();
            java.util.List<InboxItem> pageItems = scrapeRowsOnPage(page, remaining);
            if (pageItems.isEmpty()) break;
            all.addAll(pageItems);
            if (all.size() >= maxEmailsRanged) break;
            // Try to advance to the next page; bail if Gmail has no more.
            if (!navigateToRangeSearch(r, page + 1)) break;
            page++;
        }
        logger.info("EmailReader: range={} → scraped {} emails across {} page(s)",
                r, all.size(), page);
        return all;
    }

    /**
     * Scrape the currently-visible Gmail listing — search results or default
     * inbox alike — and emit InboxItems with id format {@code "<page>:<domIdx>"}.
     * Capped at {@code cap} rows so we don't overshoot the per-call budget.
     */
    private List<InboxItem> scrapeRowsOnPage(int page, int cap) {
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
                "    const time    = r.querySelector('span.xW span')?.innerText\n" +
                "                 ?? r.querySelector('span.xW')?.innerText\n" +
                "                 ?? r.querySelector('span[title]:not([email])')?.getAttribute('title')\n" +
                "                 ?? r.querySelector('td[title]')?.getAttribute('title')\n" +
                "                 ?? r.querySelector('span.xY span')?.innerText\n" +
                "                 ?? '';\n" +
                "    if (sender || subject) out.push({domIndex: '" + page + ":' + idx, sender, subject, snippet, time: (time || '').trim()});\n" +
                "  });\n" +
                "  return JSON.stringify(out.slice(0, " + cap + "));\n" +
                "})()";
        Object raw = browser.execute(Map.of("operation", "evaluate_js", "code", js));
        return parseInboxItems(String.valueOf(raw));
    }

    /**
     * Opens the email at {@code item.position()} (the "page:idx" DOM address
     * the scrape returned), scrapes its body, returns to inbox. Synchronized
     * for the same reason as {@code listInbox} — exclusive Chrome-page
     * access during the click + scrape.
     *
     * <p>Note: prior versions took just the {@code id} String and parsed it
     * as "page:idx". Since the id is now a stable content hash, the DOM
     * position arrives via the {@link InboxItem#position()} field instead.
     */
    public synchronized String readEmail(InboxItem item) {
        if (item == null) return "";
        String position = item.position();
        if (position == null || position.isBlank()) return "";
        int page = 1;
        int idx;
        try {
            int colon = position.indexOf(':');
            if (colon > 0) {
                page = Integer.parseInt(position.substring(0, colon));
                idx  = Integer.parseInt(position.substring(colon + 1));
            } else {
                idx = Integer.parseInt(position);
            }
        } catch (NumberFormatException e) {
            return "";
        }

        // For ranged listings, navigate back to the exact search/page so
        // rows[idx] points at the same email we listed. For the default
        // inbox path (page=1, no lastRangeQuery), use ensureOnInboxListing.
        // forceRefresh=false here: the listing already pulled fresh data;
        // body reads only need to be ON the right page, not re-fetch it.
        // Clicking Gmail's Refresh between every email cost ~8s per email
        // and was dominating per-row latency in production logs.
        if (page > 1 || lastRangeQuery != null) {
            navigateToRangeSearch(lastRangeQuery == null ? "1d" : lastRangeQuery, page, false);
        } else {
            // Defensive: make sure we're on the inbox listing before clicking
            // rows[idx]. Cheap when we already are (single probe call); recovers
            // when a previous readEmail's hash-nav-back didn't render fast enough.
            ensureOnInboxListing(15_000);
        }

        // CRITICAL — stale-DOM avoidance. Gmail's SPA caches previously-opened
        // conversation panels in the DOM (their {@code div.adn} stays after we
        // hash-nav back to the listing). If we just queried 'div.adn' next, we'd
        // pull the PREVIOUS email's body. So before the click, tag every
        // existing div.adn as 'stale', then later scrape only the fresh one.
        // This is what the old hard navigate() got us for free (full DOM reset);
        // we now do it explicitly to keep the navigate-skip optimisation safe.
        browser.execute(Map.of("operation", "evaluate_js",
                "code", "(() => { document.querySelectorAll('div.adn').forEach(e => e.setAttribute('data-imb-stale','1')); return true; })()"));

        // Click the row that actually IS this email — match by sender+subject,
        // not by position. Position can drift between listing-scrape time and
        // read time (e.g. a new email lands and bumps every row down by one).
        // A pure positional click in that case opens a DIFFERENT email's body
        // and the LLM enriches the wrong content under the right id — exactly
        // the bug that produced "Meetup row → Chrysanthy gym drafts". The
        // positional click is kept as a last-resort fallback.
        String clickJs = String.format(
                "(() => {" +
                "  const targetSubj = %s;" +
                "  const targetSender = %s;" +
                "  const rows = document.querySelectorAll('[role=\"main\"] [role=\"row\"]');" +
                "  const norm = s => (s || '').trim().toLowerCase().replace(/\\s+/g, ' ');" +
                "  let bestMatch = null;" +
                "  for (let i = 1; i < rows.length; i++) {" +
                "    const r = rows[i];" +
                "    const subj = norm(r.querySelector('span.bog')?.innerText);" +
                "    const sender = norm(r.querySelector('[email]')?.getAttribute('name')" +
                "                       || r.querySelector('[email]')?.getAttribute('email')" +
                "                       || r.querySelector('span.bA4')?.innerText);" +
                "    if (subj === targetSubj && sender === targetSender) {" +
                "      bestMatch = r; break;" +
                "    }" +
                "  }" +
                "  if (bestMatch) { bestMatch.click(); return 'subject-match'; }" +
                "  const fallback = rows[%d];" +
                "  if (!fallback) return 'no-row';" +
                "  fallback.click(); return 'positional';" +
                "})()",
                toJsString(item.subject()),
                toJsString(item.sender()),
                idx);
        Object clicked = browser.execute(Map.of("operation", "evaluate_js", "code", clickJs));
        String clickResult = String.valueOf(clicked);
        if ("no-row".equals(clickResult) || "false".equals(clickResult)) {
            logger.warn("EmailReader: couldn't click row position={} ({})", idx, clickResult);
            return "";
        }
        if ("positional".equals(clickResult)) {
            // Subject+sender didn't match the listing — the page has reordered
            // since scrape. We clicked rows[idx] anyway; flag it so callers can
            // see the drift in the log if the eventual body looks wrong.
            logger.warn("EmailReader: subject/sender match failed for id={} (subj='{}', sender='{}') — fell back to positional click at {}",
                    item.id(), excerpt(item.subject(), 60), excerpt(item.sender(), 40), idx);
        } else {
            logger.debug("EmailReader: clicked by {} for id={}", clickResult, item.id());
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

        // Hash-only "navigate back" to the listing — back to the SAME page
        // we read from when ranged (so the next readEmail's rows[idx] still
        // resolves correctly), back to plain #inbox in the legacy default.
        String backHash;
        if (lastRangeQuery != null) {
            String base = "#search/in%3Ainbox+newer_than%3A" + lastRangeQuery;
            backHash = page <= 1 ? base : (base + "/p" + page);
        } else {
            backHash = "#inbox";
        }
        final String backHashFinal = backHash;
        browser.execute(Map.of("operation", "evaluate_js",
                "code", "(() => { try { if (location.hash !== '" + backHashFinal + "') location.hash = '" + backHashFinal + "'; return true; } catch (e) { return false; } })()"));
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

    /**
     * Hash-route Gmail's SPA to a date-range search inside the inbox.
     * Encodes the search query as {@code in:inbox newer_than:<range>} —
     * Gmail's standard search syntax — and waits for the results table.
     *
     * <p>Uses hash-only navigation when we're already on Gmail (cheap —
     * the SPA re-routes without a full page reload). Hard-navigates only
     * when we have to. Critically, {@link #forceGmailRefetch()} is always
     * called at the end so Gmail re-runs the search even when the hash
     * hasn't changed since last call (otherwise we'd just rescrape the
     * stale DOM rows from the previous request — that bug was visible in
     * production logs as repeat range=7d calls finishing in 1–2 s with no
     * actual refresh).
     */
    /**
     * Convenience overload — preserves the old behaviour (page-1 always
     * force-refreshes via Gmail's own Refresh button). Used by the listing
     * scrape, where we genuinely want fresh data.
     */
    private boolean navigateToRangeSearch(String range, int page) {
        return navigateToRangeSearch(range, page, true);
    }

    /**
     * Hash-route Gmail to the {@code in:inbox newer_than:<range>} search,
     * page {@code page} (1-indexed). Returns {@code true} if rows for that
     * page rendered; {@code false} if Gmail has no more results.
     *
     * <p>{@code forceRefresh} controls whether we also click Gmail's own
     * Refresh button on page 1. {@link #listInbox(String)} passes
     * {@code true} so a repeat press of the same range still pulls fresh
     * data. {@link #readEmail(String)} passes {@code false} — it only
     * needs to be ON the page to click {@code rows[idx]}; clicking
     * Refresh between every email read costs ~8 s per email and was
     * dominating reader latency in production logs.
     */
    private boolean navigateToRangeSearch(String range, int page, boolean forceRefresh) {
        String baseHash = "#search/in%3Ainbox+newer_than%3A" + range;
        String hash = page <= 1 ? baseHash : (baseHash + "/p" + page);

        String hashChangeJs =
                "(() => { try {"
                + "  const target = '" + hash + "';"
                + "  if (location.hostname.indexOf('mail.google.com') < 0) return 'navigate';"
                + "  if (location.hash === target) {"
                + "    location.hash = '#inbox';"
                + "    location.hash = target;"
                + "    return 'toggled';"
                + "  }"
                + "  location.hash = target;"
                + "  return 'hashed';"
                + "} catch (e) { return 'navigate'; } })()";
        String mode;
        try {
            mode = String.valueOf(browser.execute(Map.of("operation", "evaluate_js", "code", hashChangeJs)));
        } catch (Exception e) {
            mode = "navigate";
        }
        if ("navigate".equals(mode)) {
            try {
                browser.execute(Map.of("operation", "navigate",
                        "url", "https://mail.google.com/mail/u/0/" + hash,
                        "timeout_ms", 10_000));
            } catch (Exception e) {
                logger.warn("EmailReader: hard-navigate to {} failed: {}", hash, e.getMessage());
            }
        }
        try {
            browser.execute(Map.of("operation", "wait_for",
                    "selector", "[role=\"main\"] [role=\"row\"]",
                    "timeout_ms", 10_000));
        } catch (Exception e) {
            // Likely end-of-pagination: empty results page → no rows ever appear.
            logger.info("EmailReader: range={} page={} returned no rows (end of results)", range, page);
            return false;
        }
        if (page <= 1 && forceRefresh) {
            // Force Gmail to re-run the search on page 1 every time, so a
            // repeat press of the same range still pulls fresh data.
            forceGmailRefetch();
        }
        // Verify there's actually at least one DATA row beyond the header —
        // a "no results" page still has the table shell + header.
        String countJs = "(() => document.querySelectorAll('[role=\"main\"] [role=\"row\"]').length)()";
        try {
            Object n = browser.execute(Map.of("operation", "evaluate_js", "code", countJs));
            int rows = Integer.parseInt(String.valueOf(n));
            if (rows <= 1) return false;
        } catch (Exception ignored) { /* continue */ }
        logger.info("EmailReader: range={} page={} mode={} → {}", range, page, mode, hash);
        return true;
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
    public synchronized boolean injectReply(InboxItem item, String draftText) {
        try {
            return injectReplyInner(item, draftText);
        } catch (Throwable t) {
            // Any unexpected throw (BrowserTool internals, OOM, etc.) collapses
            // to a clean false — the controller surfaces it to the user as
            // "Gmail DOM didn't respond …" instead of a 500.
            logger.warn("EmailReader.injectReply: unhandled error for id={} ({})",
                    item == null ? "?" : item.id(), t.toString());
            return false;
        }
    }

    private boolean injectReplyInner(InboxItem item, String draftText) {
        if (draftText == null || draftText.isBlank()) return false;
        if (item == null) return false;
        String position = item.position();
        if (position == null || position.isBlank()) return false;

        // position is "page:idx" — mirrors readEmail's parsing.
        int page = 1;
        int idx;
        try {
            int colon = position.indexOf(':');
            if (colon > 0) {
                page = Integer.parseInt(position.substring(0, colon));
                idx  = Integer.parseInt(position.substring(colon + 1));
            } else {
                idx = Integer.parseInt(position);
            }
        } catch (NumberFormatException e) {
            return false;
        }

        // Make sure Chrome is on the same listing/page the id was issued from,
        // otherwise rows[idx] points at a different email. forceRefresh=false:
        // we just need to be ON the page, not re-run the search.
        if (page > 1 || lastRangeQuery != null) {
            navigateToRangeSearch(lastRangeQuery == null ? "1d" : lastRangeQuery, page, false);
        } else {
            ensureOnInboxListing(15_000);
        }

        // Stale-DOM guard: Gmail keeps previously-opened conversation panels
        // (their div.adn) in the DOM. Without tagging them first, the next
        // query — including the Reply-button lookup below — could resolve to
        // the PREVIOUS email instead of the one we just clicked.
        browser.execute(Map.of("operation", "evaluate_js",
                "code", "(() => { document.querySelectorAll('div.adn').forEach(e => e.setAttribute('data-imb-stale','1')); return true; })()"));

        // 1. Click the row that IS this email — match by subject + sender
        //    first so we open the right one even if the listing reordered
        //    since the scrape that produced item.position(). Same logic as
        //    readEmail; see the longer rationale there.
        String clickJs = String.format(
                "(() => {" +
                "  const targetSubj = %s;" +
                "  const targetSender = %s;" +
                "  const rows = document.querySelectorAll('[role=\"main\"] [role=\"row\"]');" +
                "  const norm = s => (s || '').trim().toLowerCase().replace(/\\s+/g, ' ');" +
                "  let bestMatch = null;" +
                "  for (let i = 1; i < rows.length; i++) {" +
                "    const r = rows[i];" +
                "    const subj = norm(r.querySelector('span.bog')?.innerText);" +
                "    const sender = norm(r.querySelector('[email]')?.getAttribute('name')" +
                "                       || r.querySelector('[email]')?.getAttribute('email')" +
                "                       || r.querySelector('span.bA4')?.innerText);" +
                "    if (subj === targetSubj && sender === targetSender) {" +
                "      bestMatch = r; break;" +
                "    }" +
                "  }" +
                "  if (bestMatch) { bestMatch.click(); return 'subject-match'; }" +
                "  const fallback = rows[%d];" +
                "  if (!fallback) return 'no-row';" +
                "  fallback.click(); return 'positional';" +
                "})()",
                toJsString(item.subject()),
                toJsString(item.sender()),
                idx);
        Object clicked = browser.execute(Map.of("operation", "evaluate_js", "code", clickJs));
        String clickResult = String.valueOf(clicked);
        if ("no-row".equals(clickResult) || "false".equals(clickResult)) {
            logger.warn("EmailReader.injectReply: couldn't click row position={} ({})", idx, clickResult);
            return false;
        }
        if ("positional".equals(clickResult)) {
            logger.warn("EmailReader.injectReply: subject/sender match failed for id={} — fell back to positional click. "
                    + "Aborting inject so we don't paste this draft into the wrong reply window.",
                    item.id());
            return false;
        }

        // Poll for a FRESH div.adn before continuing, so step 2's Reply-button
        // search resolves inside the new email's wrapper, not a stale one.
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
            logger.warn("EmailReader.injectReply: fresh email body didn't appear for row {}", idx);
            return false;
        }

        // 2. Click the inline Reply button. Three strategies tried in order
        //    against (a) the FRESH conversation wrapper, then (b) the whole doc:
        //      (i)  aria-label patterns (works on English Gmail with the
        //           standard labels: "Reply", "Reply to message")
        //      (ii) the inline reply zone ({@code div.amn} / {@code div.aDh})
        //      (iii) text-content scan over every role=button in the wrapper —
        //           catches buttons whose aria-label has rotated as long as
        //           their visible text is still "Reply" (NOT "Reply all").
        //    Returns a diagnostic string so the warn log says WHICH path
        //    matched (or which were tried but missed), not just yes/no.
        String replyJs =
                "(() => {\n" +
                "  const fresh = Array.from(document.querySelectorAll('div.adn'))\n" +
                "      .filter(e => !e.hasAttribute('data-imb-stale'));\n" +
                "  const wrapper = fresh.length > 0 ? fresh[fresh.length - 1] : null;\n" +
                "  const tried = [];\n" +
                "  const sels = [\n" +
                "    'div[role=\"button\"][aria-label^=\"Reply\"][aria-label$=\"message\"]',\n" +
                "    'div[role=\"button\"][aria-label=\"Reply\"]',\n" +
                "    'span[role=\"link\"][aria-label^=\"Reply\"]',\n" +
                "    'div.amn div[role=\"button\"]:not([aria-label*=\"all\" i]):not([aria-label*=\"forward\" i])',\n" +
                "    'div.aDh div[role=\"button\"]:not([aria-label*=\"all\" i]):not([aria-label*=\"forward\" i])',\n" +
                "    'div.aH4 div[role=\"button\"]:not([aria-label*=\"all\" i]):not([aria-label*=\"forward\" i])'\n" +
                "  ];\n" +
                "  for (const sel of sels) {\n" +
                "    const scopes = wrapper ? [wrapper, document] : [document];\n" +
                "    for (const scope of scopes) {\n" +
                "      const btn = scope.querySelector(sel);\n" +
                "      if (btn) { btn.click(); return 'ok:aria:' + sel; }\n" +
                "    }\n" +
                "    tried.push(sel);\n" +
                "  }\n" +
                "  // Text-content scan: every actionable element under the wrapper.\n" +
                "  // Matches case-insensitively on exact \"reply\" or \"reply (shift+r)\",\n" +
                "  // and explicitly rejects anything with \"all\" or \"forward\" in either\n" +
                "  // the label or visible text. Doc-wide as the last resort.\n" +
                "  const scopes = wrapper ? [wrapper, document.body] : [document.body];\n" +
                "  for (const scope of scopes) {\n" +
                "    const cands = scope.querySelectorAll('[role=\"button\"], [role=\"link\"], button');\n" +
                "    for (const btn of cands) {\n" +
                "      if (btn.offsetWidth === 0 && btn.offsetHeight === 0) continue;\n" +
                "      const text = ((btn.textContent || '').trim()).toLowerCase();\n" +
                "      const al   = ((btn.getAttribute('aria-label') || '').toLowerCase());\n" +
                "      const isReply = text === 'reply' || al === 'reply'\n" +
                "                   || /^reply\\s*(\\(|$)/.test(al)\n" +
                "                   || /^reply\\s*(\\(|$)/.test(text);\n" +
                "      const isExcluded = text.includes('all') || al.includes('all')\n" +
                "                      || text.includes('forward') || al.includes('forward');\n" +
                "      if (isReply && !isExcluded) {\n" +
                "        btn.click();\n" +
                "        return 'ok:text:' + (al || text);\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "  return 'miss:tried=' + tried.length + ':text-scan-empty';\n" +
                "})()";
        Object replyClicked = browser.execute(Map.of("operation", "evaluate_js", "code", replyJs));
        String result = String.valueOf(replyClicked);
        if (!result.startsWith("ok:")) {
            logger.warn("EmailReader.injectReply: couldn't find Reply button for row {} ({})",
                    idx, result);
            return false;
        }
        logger.debug("EmailReader.injectReply: Reply path → {}", result);

        // 3. Wait for the compose textbox to materialise.
        try {
            browser.execute(Map.of("operation", "wait_for",
                    "selector", "div[role=\"textbox\"][aria-label*=\"Message Body\"], div[g_editable=\"true\"][role=\"textbox\"]",
                    "timeout_ms", 15_000));
        } catch (Exception e) {
            logger.warn("EmailReader.injectReply: compose textbox didn't render ({})", e.getMessage());
            return false;
        }

        // 4. Inject the draft. Gmail now enforces Trusted Types — assigning
        //    a plain string to .innerHTML throws "This document requires
        //    'TrustedHTML' assignment". So we avoid innerHTML entirely:
        //      - Primary path: document.execCommand('insertText') after
        //        clearing the editor. Built-in to contenteditable, dispatches
        //        the InputEvents Gmail's autosaver subscribes to, gives the
        //        user a clean Undo step, and is exempt from Trusted Types.
        //      - Fallback: DOM-API construction (textContent + createElement('br'))
        //        for browsers/profiles where execCommand returns false. Also
        //        Trusted-Types-safe because we never touch innerHTML.
        //
        //    Jackson serializes the text safely as a JS string literal —
        //    handles quotes, newlines, unicode without bespoke escaping.
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
                  if (!editor) return 'no-editor';
                  editor.focus();

                  // Primary: execCommand-based insert. Selects all current
                  // content, deletes it, then inserts the draft text.
                  // execCommand returns false in browsers that have dropped
                  // it — we fall through to the DOM path in that case.
                  let used = 'execCommand';
                  let ok = false;
                  try {
                    document.execCommand('selectAll', false, null);
                    document.execCommand('delete', false, null);
                    ok = document.execCommand('insertText', false, text);
                  } catch (e) { ok = false; }

                  if (!ok) {
                    used = 'dom-api';
                    // Clear via textContent (Trusted-Types-safe — it's plain text).
                    editor.textContent = '';
                    const lines = text.split('\\n');
                    lines.forEach((line, i) => {
                      if (i > 0) editor.appendChild(document.createElement('br'));
                      if (line.length > 0) editor.appendChild(document.createTextNode(line));
                    });
                    // Move caret to end so the user types after the inserted text.
                    const range = document.createRange();
                    range.selectNodeContents(editor);
                    range.collapse(false);
                    const sel = window.getSelection();
                    sel.removeAllRanges();
                    sel.addRange(range);
                    // Fire the input event Gmail listens to for autosave.
                    editor.dispatchEvent(new InputEvent('input',
                      { bubbles: true, inputType: 'insertText', data: text }));
                  }
                  return 'ok:' + used;
                })()
                """.formatted(jsLiteral);
        Object injected = browser.execute(Map.of("operation", "evaluate_js", "code", injectJs));
        String injectResult = String.valueOf(injected);
        boolean ok = injectResult != null && injectResult.startsWith("ok:");
        if (!ok) {
            logger.warn("EmailReader.injectReply: text injection failed for row {} ({})",
                    idx, injectResult);
        } else {
            logger.debug("EmailReader.injectReply: injected via {}", injectResult);
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
                String position = dom == null ? "" : dom.toString();
                if (position.isBlank()) continue;
                // Stable id derived from sender + subject + snippet so the
                // same email keeps the same id across listings, sessions,
                // and Gmail re-orderings. Position is kept separately for
                // DOM clicks but is no longer the cache key.
                out.add(InboxItem.from(
                        position,
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

    /** JSON-encode a Java string as a JS string literal — handles quotes,
     *  backslashes, newlines, unicode without bespoke escaping. Used for
     *  splicing user-content fields (subject, sender) into evaluate_js code.
     *  Returns the literal {@code ""} when serialization fails (defensive;
     *  the surrounding JS will then mismatch and the click falls back to
     *  positional, which is still safer than smuggling unescaped text into
     *  the JS source). The matching logic in JS normalises the same way
     *  (trim + lowercase + collapse whitespace). */
    private static String toJsString(String s) {
        try {
            return JSON.writeValueAsString(s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", " "));
        } catch (Exception e) {
            return "\"\"";
        }
    }

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
