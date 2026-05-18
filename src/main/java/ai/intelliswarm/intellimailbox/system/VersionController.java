package ai.intelliswarm.intellimailbox.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Read-only "what version are we and is there a newer one" endpoint.
 *
 * <pre>
 *   GET /api/version
 *   → { current: "0.1.3", latest: "0.1.4", updateAvailable: true,
 *       releaseUrl: "https://github.com/.../releases/tag/v0.1.4" }
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class VersionController {

    private final UpdateChecker updateChecker;

    public VersionController(UpdateChecker updateChecker) {
        this.updateChecker = updateChecker;
    }

    @GetMapping("/version")
    public Map<String, Object> version() {
        UpdateChecker.VersionInfo v = updateChecker.snapshot();
        Map<String, Object> out = new HashMap<>();
        out.put("current", v.current());
        out.put("latest", v.latest());
        out.put("updateAvailable", v.updateAvailable());
        out.put("releaseUrl", v.releaseUrl());
        // Asset metadata for the in-app "Download & install" flow. Null
        // values are kept (rather than dropped) so the UI's typed model
        // can distinguish "not yet checked" from "no asset for this OS".
        out.put("assetUrl", v.assetUrl());
        out.put("assetName", v.assetName());
        out.put("assetSize", v.assetSize());
        return out;
    }
}
