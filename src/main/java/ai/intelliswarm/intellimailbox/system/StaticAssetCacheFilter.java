package ai.intelliswarm.intellimailbox.system;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Cache-control headers for the static frontend, so an upgrade from one
 * installed version to another never strands users on a blank page.
 *
 * <p>Background: Angular emits {@code index.html} referencing hashed bundle
 * filenames like {@code main-KBZVVD3X.js}. Every build produces fresh hashes,
 * so old filenames disappear from the new MSI's jar. If the browser cached
 * the previous index.html (Spring Boot serves static resources with no
 * Cache-Control by default, leaving caching policy entirely to the browser
 * heuristics — which gladly cache HTML for tens of minutes), the post-upgrade
 * Chrome window asks for the old bundle, gets a 404, and renders nothing.
 *
 * <p>Two-tier policy:
 * <ul>
 *   <li><b>index.html (and the "/" welcome route that maps to it):</b>
 *       {@code no-cache, no-store, must-revalidate} — the browser must
 *       re-fetch on every load. The file is ~2 KB so the cost is trivial.</li>
 *   <li><b>Hashed bundle assets (<code>name-HASH.ext</code>):</b>
 *       {@code public, max-age=31536000, immutable} — safe to cache for a
 *       year because the hash changes any time the contents do. Saves the
 *       browser from re-downloading the 1–2 MB JS bundle on every page load.</li>
 * </ul>
 *
 * <p>Everything else (API responses, non-hashed PNGs) is left alone — those
 * paths choose their own caching, and the filter only ever sets headers
 * before passing the request down the chain so downstream handlers can still
 * override if they need to.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StaticAssetCacheFilter extends OncePerRequestFilter {

    /** Matches the Angular CLI's hashed bundle filename pattern, e.g.
     *  {@code main-KBZVVD3X.js}, {@code styles-FRZNI5HW.css},
     *  {@code polyfills-FFHMD2TL.js}. Eight or more uppercase
     *  alphanumeric characters after a dash, immediately before a known
     *  static extension. Lower-case hashes (older CLIs) are also caught. */
    private static final Pattern HASHED_ASSET = Pattern.compile(
            ".*-[A-Za-z0-9]{8,}\\.(js|css|svg|woff|woff2|ttf|eot)$");

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = req.getRequestURI();
        if ("/".equals(uri) || "/index.html".equals(uri)) {
            resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            resp.setHeader("Pragma", "no-cache");
            resp.setHeader("Expires", "0");
        } else if (HASHED_ASSET.matcher(uri).matches()) {
            // immutable lets even busy browsers skip the conditional GET
            // entirely — the hashed name is the version identifier.
            resp.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        }
        chain.doFilter(req, resp);
    }
}
