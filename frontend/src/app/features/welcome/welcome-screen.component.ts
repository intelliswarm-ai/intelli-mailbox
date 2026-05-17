import {
  Component, EventEmitter, OnDestroy, OnInit, Output, computed, inject, signal,
} from '@angular/core';
import { Subscription } from 'rxjs';
import { DiagnosticsService } from '../../core/diagnostics.service';
import {
  DiagnosticCheck, DiagnosticsResponse, FixProgress,
} from '../../core/diagnostics.types';

interface ProgressState {
  percent: number;
  message: string;
}

const POLL_INTERVAL_MS = 4_000;
const AUTO_START_SECONDS = 3;

const STREAMED_ACTIONS = new Set(['pull-model', 'pull-embedding-model', 'install-ollama', 'start-ollama']);

const STATUS_ICON: Record<string, string> = {
  ok:   '✓',
  warn: '!',
  fail: '✕',
  skip: '·',
};

/** Friendly labels for each auto-fix action. Falls back to a generic verb. */
function autoFixLabel(action: string): string {
  switch (action) {
    case 'pull-model':           return '↓ Download AI model now';
    case 'pull-embedding-model': return '↓ Download chat search model (270 MB)';
    case 'install-ollama':       return '↓ Install Ollama for me';
    case 'start-ollama':         return '▶ Start Ollama for me';
    case 'open-gmail':           return '↗ Open Gmail in Chrome';
    case 'open-ollama-download': return '↗ Open Ollama download page';
    default:                     return '🛠 Fix automatically';
  }
}

/**
 * First-run / cold-start system check. Replaces the legacy welcome-screen
 * diagnostics panel.
 *
 * Flow:
 *   1. GET /api/diagnostics → render checklist (OK/warn/fail/skip rows)
 *   2. Each non-OK row gets an Auto-fix button (when canAutoFix=true) plus
 *      an optional fixCommand chip (copy-pasteable shell) and helpUrl link.
 *   3. Long-running fixes (pull-model, install-ollama) stream progress via
 *      SSE — we render a per-row progress bar.
 *   4. When all checks pass, a 3-second countdown auto-emits (ready) so the
 *      shell can switch to the inbox view. User can hit "Start now" or
 *      "Cancel" to override.
 *   5. While not ready, we re-poll every 4s to catch external state changes
 *      (user signs in to Gmail, completes Ollama install, etc.).
 */
@Component({
  selector: 'im-welcome-screen',
  standalone: true,
  template: `
    <div class="panel">
      @if (data(); as d) {
        <div class="header">
          <div class="title">System check</div>
          <div class="summary" [class.ok]="d.ready">
            @if (d.ready) {
              ✓ All {{ d.counts.total }} checks passed — you're ready to go.
            } @else {
              {{ d.counts.ok }} of {{ d.counts.total }} ready — finish the steps below to start.
            }
          </div>
          <button class="recheck" (click)="rerun()">↻ Re-check</button>
        </div>

        @for (c of d.checks; track c.id) {
          <div class="row" [attr.data-status]="c.status">
            <span class="icon" [attr.data-status]="c.status">{{ statusIcon[c.status] || '·' }}</span>
            <div class="body">
              <div class="label">{{ c.label }}</div>
              <div class="msg">{{ c.message }}</div>
              @if (c.status !== 'ok' && c.hint) {
                <div class="hint">{{ c.hint }}</div>
              }
              @if (c.status !== 'ok' && (c.canAutoFix || c.helpUrl || c.fixCommand)) {
                <div class="actions">
                  @if (c.canAutoFix && c.autoFixAction) {
                    <button type="button"
                            [disabled]="actionInFlight() === c.autoFixAction || !!actionResult()[c.autoFixAction!]"
                            [class.ok]="actionResult()[c.autoFixAction!]?.ok === true"
                            [class.err]="actionResult()[c.autoFixAction!]?.ok === false"
                            (click)="runAction(c.autoFixAction!, c)">
                      @if (actionResult()[c.autoFixAction!]; as r) {
                        {{ r.text }}
                      } @else if (actionInFlight() === c.autoFixAction) {
                        ⌛ Running…
                      } @else {
                        {{ labelFor(c.autoFixAction!) }}
                      }
                    </button>
                  }
                  @if (c.helpUrl) {
                    <a class="help-link" [href]="c.helpUrl" target="_blank" rel="noopener">
                      Or do it manually →
                    </a>
                  }
                  @if (c.fixCommand) {
                    <code>{{ c.fixCommand }}</code>
                  }
                </div>
              }
              @if (progress()[c.id]; as p) {
                <div class="progress-mount">
                  <div class="progress-bar"><div class="progress-fill" [style.width.%]="p.percent"></div></div>
                  <div class="progress-text">{{ p.message }}</div>
                </div>
              }
            </div>
          </div>
        }

        @if (d.ready) {
          <div class="cta">
            @if (countdown() !== null) {
              <div class="countdown">
                Starting your inbox in <b>{{ countdown() }}</b>…
                <button type="button" class="link" (click)="cancelCountdown()">Cancel</button>
              </div>
            }
            <button class="primary" (click)="startNow()">↻ Start now</button>
          </div>
        }
      } @else if (errorMsg()) {
        <div class="loading fail">
          Couldn't run diagnostics: {{ errorMsg() }}
          <button class="recheck" (click)="rerun()">↻ Try again</button>
        </div>
      } @else {
        <div class="loading">Checking your system…</div>
      }
    </div>
  `,
  styles: [`
    .panel {
      max-width: 720px;
      margin: 60px auto;
      padding: 28px 28px;
      background: var(--bg-card);
      border: 1px solid var(--border-subtle);
      border-radius: 14px;
      box-shadow: var(--shadow-card);
    }
    .loading {
      text-align: center;
      color: var(--text-muted);
      padding: 30px 0;
    }
    .loading.fail { color: var(--color-error); }

    .header {
      display: grid;
      grid-template-columns: 1fr auto;
      gap: 8px 12px;
      align-items: baseline;
      margin-bottom: 18px;
      padding-bottom: 14px;
      border-bottom: 1px solid var(--border-subtle);
    }
    .title {
      grid-column: 1 / 2;
      font-size: 1.1rem; font-weight: 600;
      color: var(--text-heading);
    }
    .summary {
      grid-column: 1 / 2;
      font-size: 0.88rem;
      color: var(--text-secondary);
    }
    .summary.ok { color: var(--color-success); font-weight: 500; }
    .recheck {
      grid-column: 2 / 3;
      grid-row: 1 / 3;
      align-self: center;
      padding: 6px 12px;
      border-radius: 8px;
      background: transparent;
      color: var(--text-secondary);
      border: 1px solid var(--border-subtle);
      font-size: 0.82rem;
      font-family: inherit;
      cursor: pointer;
    }
    .recheck:hover { color: var(--text-heading); border-color: var(--accent); }

    .row {
      display: grid;
      grid-template-columns: 28px 1fr;
      gap: 10px;
      padding: 12px 0;
      border-top: 1px solid var(--border-subtle);
    }
    .row:first-of-type { border-top: 0; }

    .icon {
      width: 22px; height: 22px;
      border-radius: 50%;
      display: inline-flex; align-items: center; justify-content: center;
      font-size: 0.78rem; font-weight: 700;
      color: #ffffff;
    }
    .icon[data-status="ok"]   { background: var(--color-success); }
    .icon[data-status="warn"] { background: var(--color-warning); color: #1a1a1a; }
    .icon[data-status="fail"] { background: var(--color-error); }
    .icon[data-status="skip"] { background: var(--text-muted); }

    .label { font-weight: 600; color: var(--text-heading); font-size: 0.92rem; }
    .msg { color: var(--text-secondary); font-size: 0.88rem; margin-top: 2px; }
    .hint {
      margin-top: 6px;
      padding: 8px 10px;
      background: var(--bg-input);
      border-radius: 6px;
      font-size: 0.82rem;
      color: var(--text-secondary);
      line-height: 1.4;
      white-space: pre-wrap;
    }
    .actions {
      margin-top: 8px;
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
    }
    .actions button {
      padding: 6px 12px;
      border-radius: 8px;
      background: var(--accent-dim);
      color: var(--accent);
      border: 1px solid var(--accent);
      font-family: inherit;
      font-size: 0.82rem;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .actions button:hover:not(:disabled) {
      background: var(--gradient-brand);
      color: #ffffff;
      border-color: var(--accent-2);
    }
    .actions button:disabled { opacity: 0.5; cursor: not-allowed; }
    .actions button.ok {
      background: rgba(102, 187, 106, 0.10);
      color: var(--color-success);
      border-color: var(--color-success);
      opacity: 1;
    }
    .actions button.err {
      background: rgba(255, 110, 110, 0.10);
      color: var(--color-error);
      border-color: var(--color-error);
      opacity: 1;
    }
    .actions .help-link {
      color: var(--accent);
      text-decoration: none;
      font-size: 0.82rem;
    }
    .actions .help-link:hover { text-decoration: underline; }
    .actions code {
      background: var(--bg-input);
      border: 1px solid var(--border-subtle);
      padding: 4px 8px;
      border-radius: 6px;
      font-family: var(--font-mono);
      font-size: 0.78rem;
      color: var(--text-primary);
    }

    .progress-mount {
      margin-top: 10px;
    }
    .progress-bar {
      height: 6px;
      background: var(--bg-input);
      border-radius: 999px;
      overflow: hidden;
    }
    .progress-fill {
      height: 100%;
      background: var(--gradient-brand);
      transition: width 0.3s ease;
    }
    .progress-text {
      margin-top: 4px;
      font-size: 0.78rem;
      color: var(--text-muted);
    }

    .cta {
      margin-top: 20px;
      padding-top: 14px;
      border-top: 1px solid var(--border-subtle);
      display: flex; flex-direction: column; align-items: center; gap: 10px;
    }
    .countdown {
      color: var(--text-secondary);
      font-size: 0.88rem;
    }
    .countdown b { color: var(--accent); font-size: 1.05rem; }
    .countdown .link {
      all: unset; cursor: pointer;
      color: var(--accent); text-decoration: underline;
      margin-left: 8px;
    }
    .primary {
      padding: 8px 18px;
      border-radius: 8px;
      background: var(--gradient-brand);
      color: #ffffff;
      border: none;
      font-family: inherit;
      font-size: 0.9rem;
      font-weight: 500;
      cursor: pointer;
      box-shadow: 0 0 12px var(--accent-glow);
    }
  `],
})
export class WelcomeScreenComponent implements OnInit, OnDestroy {
  @Output() ready = new EventEmitter<void>();

  private diagnosticsService = inject(DiagnosticsService);

  readonly data = signal<DiagnosticsResponse | null>(null);
  readonly errorMsg = signal<string | null>(null);
  readonly progress = signal<Record<string, ProgressState>>({});
  readonly actionInFlight = signal<string | null>(null);
  /** Transient post-action feedback ("✓ Done" / "✗ Failed") keyed by action.
   *  Cleared after ~2 s so the button reverts to its normal label. Partial<>
   *  so TS treats absent keys as `undefined` rather than as the value type. */
  readonly actionResult = signal<Partial<Record<string, { ok: boolean; text: string }>>>({});
  readonly countdown = signal<number | null>(null);

  readonly statusIcon = STATUS_ICON;
  readonly labelFor = autoFixLabel;

  private subs = new Subscription();
  private pollHandle: number | null = null;
  private countdownHandle: number | null = null;
  /** Once we auto-emit ready, we don't re-schedule the countdown on subsequent polls. */
  private countdownDone = false;

  ngOnInit(): void {
    this.refresh();
  }

  ngOnDestroy(): void {
    this.subs.unsubscribe();
    this.stopPolling();
    this.cancelCountdown();
  }

  rerun(): void {
    this.errorMsg.set(null);
    this.refresh();
  }

  startNow(): void {
    this.cancelCountdown();
    this.ready.emit();
  }

  cancelCountdown(): void {
    this.countdown.set(null);
    if (this.countdownHandle !== null) {
      clearInterval(this.countdownHandle);
      this.countdownHandle = null;
    }
  }

  runAction(action: string, check: DiagnosticCheck): void {
    if (this.actionInFlight()) return;
    this.actionInFlight.set(action);

    if (STREAMED_ACTIONS.has(action)) {
      this.progress.set({
        ...this.progress(),
        [check.id]: { percent: 0, message: 'Starting…' },
      });
      let lastPhase: string | undefined;
      this.subs.add(this.diagnosticsService.streamFix(action).subscribe({
        next: (p) => {
          lastPhase = p.phase;
          this.applyProgress(check.id, p);
        },
        error: () => {
          this.applyProgress(check.id, { phase: 'error', message: 'Stream error' });
          this.actionInFlight.set(null);
          this.flashResult(action, false, '✗ Stream error');
        },
        complete: () => {
          this.actionInFlight.set(null);
          const ok = lastPhase === 'done';
          this.flashResult(action, ok, ok ? '✓ Done' : '✗ Failed');
          // Re-run diagnostics shortly after — server-side state has changed.
          setTimeout(() => this.refresh(), 1200);
        },
      }));
    } else {
      this.subs.add(this.diagnosticsService.fix(action).subscribe({
        next: (res) => {
          this.actionInFlight.set(null);
          this.flashResult(action, res.ok, res.ok
            ? `✓ ${res.message || 'Done'}`
            : `✗ ${res.reason || 'Failed'}`);
          // Re-run quickly so the row flips to its new state ASAP. For
          // open-gmail, the user still needs to sign in — the diagnostics
          // poll catches that on the next interval. For open-ollama-download
          // and similar, this immediate refresh is enough.
          setTimeout(() => this.refresh(), 400);
        },
        error: (e) => {
          this.actionInFlight.set(null);
          this.flashResult(action, false, `✗ ${e.message ?? 'Failed'}`);
        },
      }));
    }
  }

  /** Shows ✓/✗ on the button for ~2 s, then clears so it reverts to the
   *  normal label. Tracks per-action so multiple buttons can flash in parallel. */
  private flashResult(action: string, ok: boolean, text: string): void {
    this.actionResult.set({ ...this.actionResult(), [action]: { ok, text } });
    setTimeout(() => {
      const next = { ...this.actionResult() };
      delete next[action];
      this.actionResult.set(next);
    }, 2000);
  }

  private applyProgress(checkId: string, p: FixProgress): void {
    const percent = typeof p.percent === 'number' ? Math.max(0, Math.min(100, p.percent)) : this.progress()[checkId]?.percent ?? 0;
    const message = p.message ?? this.progress()[checkId]?.message ?? '';
    this.progress.set({
      ...this.progress(),
      [checkId]: { percent: p.phase === 'done' ? 100 : percent, message },
    });
  }

  private refresh(): void {
    this.subs.add(this.diagnosticsService.run().subscribe({
      next: (res) => {
        this.data.set(res);
        this.errorMsg.set(null);
        if (res.ready && !this.countdownDone) {
          this.scheduleAutoStart();
        }
        if (!res.ready && this.pollHandle === null) {
          this.startPolling();
        }
        if (res.ready) {
          this.stopPolling();
        }
      },
      error: (e) => {
        this.errorMsg.set(e?.message ?? String(e));
      },
    }));
  }

  private startPolling(): void {
    this.pollHandle = window.setInterval(() => this.refresh(), POLL_INTERVAL_MS);
  }

  private stopPolling(): void {
    if (this.pollHandle !== null) {
      clearInterval(this.pollHandle);
      this.pollHandle = null;
    }
  }

  private scheduleAutoStart(): void {
    if (this.countdownHandle !== null) return;
    this.countdownDone = true;
    let n = AUTO_START_SECONDS;
    this.countdown.set(n);
    this.countdownHandle = window.setInterval(() => {
      n -= 1;
      if (n <= 0) {
        this.cancelCountdown();
        this.ready.emit();
      } else {
        this.countdown.set(n);
      }
    }, 1000);
  }
}
