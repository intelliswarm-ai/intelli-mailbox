/**
 * Wire types for /api/diagnostics. Mirrors the DiagnosticCheck Java record
 * + the wrapper response from DiagnosticsController.diagnose().
 */

export type DiagnosticStatus = 'ok' | 'warn' | 'fail' | 'skip';

export interface DiagnosticCheck {
  id: string;
  label: string;
  status: DiagnosticStatus;
  message: string;
  hint: string | null;
  fixCommand: string | null;
  canAutoFix: boolean;
  autoFixAction: string | null;
  helpUrl: string | null;
}

export interface DiagnosticsResponse {
  checks: DiagnosticCheck[];
  ready: boolean;
  counts: { ok: number; warn: number; fail: number; total: number };
}

export interface FixResponse {
  ok: boolean;
  action: string;
  message?: string;
  reason?: string;
  url?: string;
}

/** SSE 'progress' event from /api/diagnostics/fix/pull-model and install-ollama. */
export interface FixProgress {
  percent?: number;
  message?: string;
  phase?: 'pulling' | 'installing' | 'done' | 'error' | string;
}
