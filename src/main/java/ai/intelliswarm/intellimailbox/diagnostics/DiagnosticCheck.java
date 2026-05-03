package ai.intelliswarm.intellimailbox.diagnostics;

/**
 * One row in the welcome-screen diagnostics checklist.
 *
 * <p>The frontend renders status as a coloured pill ({@code ok} → green,
 * {@code warn} → amber, {@code fail} → red, {@code skip} → muted). When
 * {@code canAutoFix} is {@code true}, a button next to the row triggers
 * {@code POST /api/diagnostics/fix/<autoFixAction>} which the backend
 * dispatches deterministically (e.g. {@code ollama pull <model>} via
 * Ollama's own HTTP API).
 */
public record DiagnosticCheck(
        String id,
        String label,
        /** "ok" | "warn" | "fail" | "skip" — used as a CSS class. */
        String status,
        String message,
        /** Multi-line user-friendly remediation guidance shown when not ok. */
        String hint,
        /** Copy-pasteable shell command that fixes the issue manually. */
        String fixCommand,
        /** When true, the UI offers an "Auto-fix" button. */
        boolean canAutoFix,
        /** Identifier the auto-fix endpoint dispatches on (when {@code canAutoFix}). */
        String autoFixAction,
        /** Friendly fallback URL — surfaced as a "Or do it manually" link when auto-fix isn't an option. */
        String helpUrl
) {
    public static DiagnosticCheck ok(String id, String label, String message) {
        return new DiagnosticCheck(id, label, "ok", message, null, null, false, null, null);
    }
    public static DiagnosticCheck warn(String id, String label, String message, String hint) {
        return new DiagnosticCheck(id, label, "warn", message, hint, null, false, null, null);
    }
    public static DiagnosticCheck fail(String id, String label, String message, String hint) {
        return new DiagnosticCheck(id, label, "fail", message, hint, null, false, null, null);
    }
    public DiagnosticCheck withFixCommand(String cmd) {
        return new DiagnosticCheck(id, label, status, message, hint, cmd, canAutoFix, autoFixAction, helpUrl);
    }
    public DiagnosticCheck withAutoFix(String action) {
        return new DiagnosticCheck(id, label, status, message, hint, fixCommand, true, action, helpUrl);
    }
    public DiagnosticCheck withHelpUrl(String url) {
        return new DiagnosticCheck(id, label, status, message, hint, fixCommand, canAutoFix, autoFixAction, url);
    }
}
