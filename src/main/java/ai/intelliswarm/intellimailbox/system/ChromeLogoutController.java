package ai.intelliswarm.intellimailbox.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * "Logout from Chrome" endpoint — closes the attached Chrome and deletes
 * every credential / cookie / cache file under the user-data-dir. The next
 * time the app starts, Chrome opens to a fresh signin screen.
 *
 * <p>Separate endpoint from {@code /api/shutdown} on purpose: shutdown
 * stops the JVM but leaves the Chrome profile intact (so re-launching just
 * resumes the session); logout wipes the profile, which is what you want
 * before handing over the machine.
 */
@RestController
@RequestMapping("/api/chrome")
public class ChromeLogoutController {

    private static final Logger logger = LoggerFactory.getLogger(ChromeLogoutController.class);

    private final ChromeSessionService session;

    public ChromeLogoutController(ChromeSessionService session) {
        this.session = session;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("profileDir",
                session.getProfileDir() == null ? null : session.getProfileDir().toString());
        return out;
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        logger.info("/api/chrome/logout requested");
        ChromeSessionService.LogoutResult r = session.logoutAndWipe();
        Map<String, Object> out = new HashMap<>();
        out.put("ok", r.ok());
        out.put("message", r.message());
        out.put("filesDeleted", r.filesDeleted());
        out.put("filesFailed", r.filesFailed());
        return ResponseEntity.ok(out);
    }
}
