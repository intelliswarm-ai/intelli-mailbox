package ai.intelliswarm.intellimailbox.system;

import ai.intelliswarm.intellimailbox.settings.LlmSettings;
import ai.intelliswarm.intellimailbox.settings.SettingsStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Lowers the OS priority of the already-running Ollama daemon so it yields
 * CPU to the user's foreground apps (browser, editor, video calls). Trades
 * email-enrichment throughput for a responsive desktop on low-end laptops.
 *
 * <p>Gated by {@code LlmSettings.ollamaBackgroundPriority} — opt-in via the
 * Settings modal. When disabled, this service does nothing.
 *
 * <p>Per-OS mechanics:
 * <ul>
 *   <li>Linux / macOS: {@code renice +10 -p <pid>} — same-user reniceing of
 *       positive nice values needs no privilege.</li>
 *   <li>Windows: {@code wmic process where "name='ollama.exe'" CALL
 *       setpriority "below normal"} — works without elevation when we own
 *       the process.</li>
 * </ul>
 *
 * <p>Best-effort: every step logs + continues on failure. The app keeps
 * working at normal Ollama priority if the platform refuses the call (some
 * corporate Windows ACLs lock {@code wmic}; some Linux distros disable
 * renice for non-root).
 *
 * <p>Companion piece: {@link #lowPriorityPrefix()} produces the shell
 * prefix the {@code start-ollama} autofix uses when it spawns Ollama
 * itself — so future launches start low-priority from the first tick
 * instead of needing a post-hoc renice.
 */
@Service
public class OllamaPriorityService {

    private static final Logger logger = LoggerFactory.getLogger(OllamaPriorityService.class);
    private static final int NICE_VALUE = 10;

    @PostConstruct
    void applyOnStartup() {
        // Converge to whatever the persisted setting says, regardless of
        // what state Ollama was left in by a previous session.
        //   setting=true  → ensure Ollama is at BelowNormal
        //   setting=false → ensure Ollama is at Normal (undoing any
        //                   BelowNormal a previous session set)
        // Without the restore branch, a user who flipped the toggle on
        // once would see Ollama stuck at BelowNormal forever — the only
        // path back to Normal would be restarting Ollama or running the
        // setpriority command manually.
        boolean want = SettingsStore.load().ollamaBackgroundPriority();
        Result r = want ? lowerPriorityNow() : restoreNormalPriority();
        logger.info("OllamaPriorityService: startup {} → {}",
                want ? "lower" : "restore", r);
    }

    /** Find every running Ollama process and drop its priority. Returns
     *  what happened so a future Settings-modal toggle can show feedback. */
    public Result lowerPriorityNow() {
        List<Long> pids = findOllamaPids();
        if (pids.isEmpty()) {
            return new Result(false, 0, "No running ollama process found.");
        }
        boolean isWindows = osName().contains("win");
        int succeeded = 0;
        for (long pid : pids) {
            boolean ok = isWindows ? lowerWindows(pid) : lowerUnix(pid);
            if (ok) succeeded++;
        }
        return new Result(succeeded > 0, succeeded,
                "Reniced " + succeeded + "/" + pids.size() + " ollama process(es) to "
                        + (isWindows ? "BelowNormal" : "nice +" + NICE_VALUE) + ".");
    }

    /**
     * Restore every Ollama process to Normal priority — the symmetric flip
     * for when the user disables background mode. Going BelowNormal →
     * Normal does NOT require elevated privileges (only AboveNormal+ does
     * on Windows; only negative nice values require root on Unix), so this
     * works the same as the lower path under the same user account.
     *
     * <p>Caveat: Ollama instances that the user launched themselves (Start
     * menu, brew services, systemd) will get raised back fine. Instances we
     * launched ourselves via {@code start-ollama} with the low-priority
     * prefix may have inherited the priority class from the launcher
     * process tree — those also restore here because we set the class
     * directly per PID rather than relying on the parent.
     */
    public Result restoreNormalPriority() {
        List<Long> pids = findOllamaPids();
        if (pids.isEmpty()) {
            return new Result(false, 0, "No running ollama process found.");
        }
        boolean isWindows = osName().contains("win");
        int succeeded = 0;
        for (long pid : pids) {
            boolean ok = isWindows ? restoreWindows(pid) : restoreUnix(pid);
            if (ok) succeeded++;
        }
        return new Result(succeeded > 0, succeeded,
                "Restored " + succeeded + "/" + pids.size() + " ollama process(es) to "
                        + (isWindows ? "Normal" : "nice 0") + ".");
    }

    /**
     * Shell prefix to launch a fresh Ollama with reduced priority.
     * Returns {@code null} when the background-priority setting is off,
     * so the caller can append the prefix unconditionally and skip
     * priority handling when the toggle is disabled.
     *
     * <p>The returned list is meant to be prepended to the
     * {@code ProcessBuilder} command. Example:
     * <pre>{@code
     *   List<String> cmd = new ArrayList<>();
     *   var prefix = svc.lowPriorityPrefix();
     *   if (prefix != null) cmd.addAll(prefix);
     *   cmd.add("ollama"); cmd.add("serve");
     * }</pre>
     */
    public List<String> lowPriorityPrefix() {
        if (!SettingsStore.load().ollamaBackgroundPriority()) return null;
        String os = osName();
        if (os.contains("win")) {
            // `cmd /c start /belownormal /b` launches the next argv with
            // reduced priority and no new console window. /b avoids a
            // visible cmd flash; /belownormal is the priority class.
            return List.of("cmd", "/c", "start", "/belownormal", "/b");
        }
        // nice -n 10 — works on macOS and every common Linux distro.
        // Positive nice values don't require root for the same-user case.
        return List.of("nice", "-n", String.valueOf(NICE_VALUE));
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private List<Long> findOllamaPids() {
        // ProcessHandle.allProcesses() is JDK-built-in, no shell needed.
        // Filter by the executable name. Matches both bare "ollama" (Linux,
        // macOS) and "ollama.exe" (Windows), with absolute paths handled
        // by basename-style matching.
        List<Long> pids = new ArrayList<>();
        try {
            ProcessHandle.allProcesses().forEach(ph -> {
                ph.info().command().ifPresent(cmd -> {
                    String name = cmd.toLowerCase();
                    // Last path segment: works for "C:\Path\ollama.exe" + "/usr/bin/ollama".
                    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
                    String base = slash >= 0 ? name.substring(slash + 1) : name;
                    if (base.equals("ollama") || base.equals("ollama.exe")) {
                        pids.add(ph.pid());
                    }
                });
            });
        } catch (Throwable t) {
            logger.debug("OllamaPriorityService: ProcessHandle scan failed ({})", t.toString());
        }
        return pids;
    }

    private boolean lowerUnix(long pid) {
        // renice +10 -p <pid> — positive values lower priority; same-user OK.
        return run(List.of("renice", "+" + NICE_VALUE, "-p", Long.toString(pid)));
    }

    private boolean lowerWindows(long pid) {
        // wmic accepts PID-scoped queries. Avoids the broader name-based
        // form so we don't accidentally renice a sibling Ollama instance
        // we didn't enumerate. Old wmic is still present on Win10/11 by
        // default; PowerShell fallback below for systems that disable it.
        boolean ok = run(List.of("wmic", "process", "where",
                "ProcessId=" + pid, "CALL", "setpriority", "16384"));
        if (ok) return true;
        // PowerShell fallback. 16384 = BelowNormal priority class constant.
        String psCmd = "Get-Process -Id " + pid
                + " | ForEach-Object { $_.PriorityClass = 'BelowNormal' }";
        return run(List.of("powershell", "-NoProfile", "-Command", psCmd));
    }

    /** Windows priority class constants:
     *   32   = Normal
     *   16384 = BelowNormal
     *   64   = Idle
     *   128  = High      (requires SeIncreaseBasePriorityPrivilege)
     *   256  = Realtime  (requires SeIncreaseBasePriorityPrivilege)
     *   32768 = AboveNormal (requires the privilege)
     * BelowNormal → Normal is allowed under standard user rights. */
    private boolean restoreWindows(long pid) {
        boolean ok = run(List.of("wmic", "process", "where",
                "ProcessId=" + pid, "CALL", "setpriority", "32"));
        if (ok) return true;
        String psCmd = "Get-Process -Id " + pid
                + " | ForEach-Object { $_.PriorityClass = 'Normal' }";
        return run(List.of("powershell", "-NoProfile", "-Command", psCmd));
    }

    private boolean restoreUnix(long pid) {
        // Set nice value back to 0 (default). Going from +10 → 0 is allowed
        // for same-user processes on every common distro / macOS.
        return run(List.of("renice", "0", "-p", Long.toString(pid)));
    }

    private boolean run(List<String> cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (var in = p.getInputStream()) { in.readAllBytes(); /* drain */ }
            boolean exited = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            logger.debug("OllamaPriorityService: {} failed ({})", cmd, t.toString());
            return false;
        }
    }

    private static String osName() { return System.getProperty("os.name", "").toLowerCase(); }

    public record Result(boolean ok, int processesReniced, String message) {}
}
