package ai.intelliswarm.intellimailbox.system;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST surface for the one-click upgrade flow (issue #3).
 *
 * <pre>
 *   POST /api/update/download → start downloading the latest release asset
 *   GET  /api/update/status   → poll progress / state machine
 *   POST /api/update/install  → launch installer + exit JVM (requires READY)
 * </pre>
 *
 * <p>The download/install logic lives in {@link UpdateInstaller}; this class
 * is intentionally thin so the controller stays trivially testable.
 */
@RestController
@RequestMapping("/api/update")
public class UpdateController {

    private final UpdateChecker checker;
    private final UpdateInstaller installer;

    public UpdateController(UpdateChecker checker, UpdateInstaller installer) {
        this.checker = checker;
        this.installer = installer;
    }

    @PostMapping("/download")
    public ResponseEntity<Map<String, Object>> download() {
        UpdateChecker.VersionInfo v = checker.snapshot();
        if (!v.updateAvailable()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error("No update is currently available."));
        }
        boolean started = installer.startDownload(v.assetUrl(), v.assetName(), v.assetSize(), v.latest());
        if (!started) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(toBody(installer.snapshot()));
        }
        return ResponseEntity.accepted().body(toBody(installer.snapshot()));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return toBody(installer.snapshot());
    }

    @PostMapping("/install")
    public ResponseEntity<Map<String, Object>> install() {
        boolean launched = installer.startInstall();
        if (!launched) {
            // 409 is the right signal for "not in the right state" — the UI
            // can distinguish this from a hard 5xx and either poll /status
            // or surface the error message verbatim.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(toBody(installer.snapshot()));
        }
        return ResponseEntity.accepted().body(toBody(installer.snapshot()));
    }

    private static Map<String, Object> toBody(UpdateInstaller.Snapshot s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", s.state().name());
        out.put("bytesRead", s.bytesRead());
        out.put("totalBytes", s.totalBytes());
        out.put("targetVersion", s.targetVersion());
        out.put("error", s.error());
        return out;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", UpdateInstaller.State.IDLE.name());
        out.put("bytesRead", 0);
        out.put("totalBytes", 0);
        out.put("targetVersion", null);
        out.put("error", message);
        return out;
    }
}
