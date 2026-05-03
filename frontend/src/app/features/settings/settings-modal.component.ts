import { Component, EventEmitter, OnInit, Output, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SettingsService } from '../../core/settings.service';
import { ProviderEntry, SaveDraft, SettingsResponse } from '../../core/settings.types';
import { ExcludedSendersPanelComponent } from './excluded-senders-panel.component';

/** Sentinel the server sends + accepts back unchanged when the user
 *  hasn't touched the masked api-key field. Defined here so the form
 *  doesn't accidentally treat a literal "…" typed by the user differently. */
const KEY_MASK_GLYPH = '…';

interface ProviderDraft {
  baseUrl: string;
  model: string;
  apiKey: string;
}

/**
 * Settings modal. Loads /api/settings on open, lets the user pick the active
 * provider (left rail), edit its connection details (right pane), tweak
 * language + debug mode, then save. Save returns restartRequired=true for
 * any provider/model/baseUrl change — surfaced via a banner.
 *
 * Deferred (will land in follow-ups): excluded-senders panel, Ollama tags
 * live model picker, per-provider reachability ping.
 */
@Component({
  selector: 'im-settings-modal',
  standalone: true,
  imports: [FormsModule, ExcludedSendersPanelComponent],
  template: `
    <div class="modal-backdrop" (click)="onBackdrop($event)">
      <div class="modal-card" (click)="$event.stopPropagation()">

        <header class="modal-header">
          <h3>⚙ Settings</h3>
          <button type="button" class="close-btn" (click)="onCancel()" aria-label="Close">✕</button>
        </header>

        @if (!data()) {
          <div class="loading">Loading…</div>
        } @else {
          @if (banner()) {
            <div class="banner" [class.err]="banner()!.kind === 'err'">
              <span [innerHTML]="banner()!.html"></span>
            </div>
          }

          <div class="modal-body">
            <!-- Provider rail -->
            <div class="rail">
              @for (p of data()!.providers; track p.id) {
                <button type="button"
                        class="rail-item"
                        [class.active]="draftActiveProvider() === p.id"
                        (click)="selectProvider(p.id)">
                  <span class="rail-icon">{{ p.icon || '·' }}</span>
                  <span class="rail-label">
                    {{ p.label }}
                    @if (p.isLocal) { <span class="rail-tag">local</span> }
                  </span>
                </button>
              }
            </div>

            <!-- Provider config pane -->
            <div class="pane">
              @if (selectedProvider(); as sel) {
                <h4 class="pane-title">{{ sel.label }}</h4>
                <p class="pane-desc">{{ sel.description }}</p>

                <label class="row">
                  <span class="row-label">Base URL</span>
                  <input type="text"
                         [ngModel]="providerDraft(sel.id).baseUrl"
                         (ngModelChange)="patchProvider(sel.id, { baseUrl: $event })"
                         [placeholder]="sel.defaultBaseUrl">
                </label>

                <label class="row">
                  <span class="row-label">Model</span>
                  <input type="text"
                         [ngModel]="providerDraft(sel.id).model"
                         (ngModelChange)="patchProvider(sel.id, { model: $event })"
                         [placeholder]="sel.defaultModel">
                </label>

                @if (sel.requiresApiKey) {
                  <label class="row">
                    <span class="row-label">API key</span>
                    <input type="password"
                           [ngModel]="providerDraft(sel.id).apiKey"
                           (ngModelChange)="patchProvider(sel.id, { apiKey: $event })"
                           placeholder="sk-…">
                  </label>
                  @if (sel.config.apiKeySet && providerDraft(sel.id).apiKey === KEY_MASK) {
                    <p class="hint">A key is already saved on disk. Leave the field unchanged to keep it.</p>
                  }
                }

                <div class="row-grid">
                  <label class="row">
                    <span class="row-label">Output language</span>
                    <select [ngModel]="languageDraft()"
                            (ngModelChange)="languageDraft.set($event)">
                      @for (lang of data()!.languageCatalog; track lang.code) {
                        <option [value]="lang.code">{{ lang.label }}</option>
                      }
                    </select>
                  </label>
                  <label class="row inline">
                    <input type="checkbox"
                           [ngModel]="debugDraft()"
                           (ngModelChange)="debugDraft.set($event)">
                    <span>Debug mode (verbose LLM logs)</span>
                  </label>
                </div>

                <p class="settings-path">
                  Saved on disk at <code>{{ data()!.settingsPath }}</code>
                </p>

                <im-excluded-senders-panel></im-excluded-senders-panel>
              }
            </div>
          </div>

          <footer class="modal-footer">
            <button type="button" class="secondary" (click)="onCancel()">Cancel</button>
            <button type="button"
                    [disabled]="saving()"
                    (click)="onSave()">
              {{ saving() ? 'Saving…' : 'Save' }}
            </button>
          </footer>
        }
      </div>
    </div>
  `,
  styles: [`
    .modal-backdrop {
      position: fixed; inset: 0; z-index: 100;
      background: rgba(0, 0, 0, 0.55);
      display: flex; align-items: center; justify-content: center;
      animation: fadein 0.15s ease;
    }
    @keyframes fadein { from { opacity: 0; } to { opacity: 1; } }

    .modal-card {
      background: var(--bg-modal);
      border: 1px solid var(--border-subtle);
      border-radius: 12px;
      width: min(880px, 92vw);
      max-height: 90vh;
      display: flex; flex-direction: column;
      color: var(--text-primary);
      box-shadow: 0 20px 60px rgba(0, 0, 0, 0.4);
      overflow: hidden;
    }

    .modal-header {
      display: flex; align-items: center; justify-content: space-between;
      padding: 18px 22px;
      border-bottom: 1px solid var(--border-subtle);
    }
    .modal-header h3 {
      margin: 0; font-size: 1.05rem; font-weight: 600;
      color: var(--text-heading);
    }
    .close-btn {
      all: unset; cursor: pointer; padding: 4px 8px;
      color: var(--text-muted); font-size: 1rem;
      border-radius: 6px;
    }
    .close-btn:hover { color: var(--text-primary); background: var(--bg-card-hover); }

    .loading {
      padding: 40px; text-align: center; color: var(--text-muted);
    }

    .banner {
      margin: 12px 22px 0;
      padding: 10px 14px;
      border-radius: 8px;
      background: var(--accent-dim);
      color: var(--text-primary);
      font-size: 0.9rem;
      line-height: 1.5;
    }
    .banner.err {
      background: rgba(255, 110, 110, 0.10);
      color: var(--color-error);
    }

    .modal-body {
      display: grid;
      grid-template-columns: 220px 1fr;
      gap: 0;
      flex: 1;
      overflow: hidden;
    }
    .rail {
      border-right: 1px solid var(--border-subtle);
      padding: 14px 8px;
      display: flex; flex-direction: column; gap: 2px;
      overflow-y: auto;
    }
    .rail-item {
      all: unset; cursor: pointer;
      display: flex; align-items: center; gap: 10px;
      padding: 8px 12px; border-radius: 8px;
      font-size: 0.88rem; color: var(--text-secondary);
    }
    .rail-item:hover { background: var(--bg-card-hover); color: var(--text-primary); }
    .rail-item.active {
      background: var(--accent-dim);
      color: var(--text-heading);
      box-shadow: inset 2px 0 0 var(--accent);
    }
    .rail-icon { font-size: 1rem; width: 20px; text-align: center; }
    .rail-label { display: inline-flex; align-items: center; gap: 6px; }
    .rail-tag {
      font-size: 0.65rem;
      color: var(--accent);
      border: 1px solid var(--accent);
      border-radius: 999px;
      padding: 0 6px;
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }

    .pane {
      padding: 18px 22px;
      overflow-y: auto;
    }
    .pane-title {
      margin: 0 0 4px;
      font-size: 1.1rem; font-weight: 600;
      color: var(--text-heading);
    }
    .pane-desc {
      margin: 0 0 18px; color: var(--text-secondary);
      font-size: 0.88rem; line-height: 1.5;
    }

    .row {
      display: flex; flex-direction: column; gap: 4px;
      margin-bottom: 12px;
    }
    .row.inline {
      flex-direction: row; align-items: center; gap: 8px;
    }
    .row-label {
      font-size: 0.78rem; font-weight: 500;
      color: var(--text-muted);
      letter-spacing: 0.02em;
    }
    .row input[type="text"],
    .row input[type="password"],
    .row select {
      background: var(--bg-input);
      border: 1px solid var(--border-subtle);
      border-radius: 8px;
      padding: 8px 12px;
      color: var(--text-primary);
      font-family: inherit; font-size: 0.9rem;
    }
    .row input:focus, .row select:focus {
      outline: none; border-color: var(--accent);
    }
    .row-grid {
      display: grid;
      grid-template-columns: 1fr auto;
      gap: 16px;
      align-items: end;
      margin-top: 18px;
      padding-top: 14px;
      border-top: 1px solid var(--border-subtle);
    }

    .hint {
      margin: 4px 0 0;
      font-size: 0.78rem; color: var(--text-muted);
    }

    .settings-path {
      margin: 18px 0 0;
      font-size: 0.78rem; color: var(--text-muted);
    }
    .settings-path code {
      font-family: var(--font-mono); font-size: 0.78rem;
      background: var(--bg-input);
      padding: 1px 6px; border-radius: 4px;
    }

    .modal-footer {
      display: flex; gap: 8px; justify-content: flex-end;
      padding: 14px 22px;
      border-top: 1px solid var(--border-subtle);
    }
    .modal-footer button {
      background: var(--accent-dim); color: var(--accent);
      border: 1px solid var(--accent);
      padding: 7px 16px; border-radius: 8px;
      font-family: inherit; font-size: 0.85rem; font-weight: 500;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .modal-footer button:hover:not(:disabled) {
      background: var(--gradient-brand); color: #ffffff;
      border-color: var(--accent-2);
    }
    .modal-footer button:disabled { opacity: 0.5; cursor: not-allowed; }
    .modal-footer button.secondary {
      background: transparent; color: var(--text-secondary);
      border-color: var(--border-subtle);
    }
    .modal-footer button.secondary:hover {
      background: var(--bg-card-hover); color: var(--text-heading);
      border-color: var(--accent);
    }
  `],
})
export class SettingsModalComponent implements OnInit {
  @Output() closed = new EventEmitter<void>();

  private settingsService = inject(SettingsService);

  readonly KEY_MASK = KEY_MASK_GLYPH;

  readonly data = signal<SettingsResponse | null>(null);
  readonly draftActiveProvider = signal<string>('');
  readonly languageDraft = signal<string>('');
  readonly debugDraft = signal<boolean>(false);
  readonly saving = signal<boolean>(false);
  readonly banner = signal<{ kind: 'info' | 'err'; html: string } | null>(null);

  /** Per-provider draft map. Lazily populated as the user clicks providers. */
  private readonly providerDrafts = signal<Record<string, ProviderDraft>>({});

  readonly selectedProvider = computed<ProviderEntry | null>(() => {
    const id = this.draftActiveProvider();
    return this.data()?.providers.find((p) => p.id === id) ?? null;
  });

  ngOnInit(): void {
    this.settingsService.load().subscribe({
      next: (res) => {
        this.data.set(res);
        this.draftActiveProvider.set(res.activeProvider);
        this.languageDraft.set(res.language);
        this.debugDraft.set(res.debugMode);
        // Seed every provider's draft with the server-side config so the masked
        // key sentinel "…" is in place for the inputs.
        const drafts: Record<string, ProviderDraft> = {};
        for (const p of res.providers) {
          drafts[p.id] = {
            baseUrl: p.config.baseUrl,
            model: p.config.model,
            apiKey: p.config.apiKeyMasked,
          };
        }
        this.providerDrafts.set(drafts);
      },
      error: (e) => {
        this.banner.set({ kind: 'err', html: `Failed to load settings: ${e.message ?? e}` });
      },
    });
  }

  selectProvider(id: string): void {
    this.draftActiveProvider.set(id);
  }

  providerDraft(id: string): ProviderDraft {
    return this.providerDrafts()[id] ?? { baseUrl: '', model: '', apiKey: '' };
  }

  patchProvider(id: string, patch: Partial<ProviderDraft>): void {
    const all = this.providerDrafts();
    this.providerDrafts.set({
      ...all,
      [id]: { ...this.providerDraft(id), ...patch },
    });
  }

  onBackdrop(_e: MouseEvent): void {
    this.onCancel();
  }

  onCancel(): void {
    this.closed.emit();
  }

  onSave(): void {
    const data = this.data();
    if (!data) return;
    this.saving.set(true);
    this.banner.set(null);
    const draft: SaveDraft = {
      activeProvider: this.draftActiveProvider(),
      providers: this.providerDrafts(),
      language: this.languageDraft(),
      debugMode: this.debugDraft(),
    };
    this.settingsService.save(draft).subscribe({
      next: (res) => {
        this.saving.set(false);
        if (!res.ok) {
          this.banner.set({ kind: 'err', html: res.reason || 'Save failed' });
          return;
        }
        const path = res.savedTo ?? '';
        this.banner.set({
          kind: 'info',
          html: `Saved to <code>${escape(path)}</code>. ` +
            `<b>Restart Intelli-mailbox</b> to apply provider/model/base-URL changes — ` +
            `language and debug mode take effect immediately.`,
        });
      },
      error: (e) => {
        this.saving.set(false);
        this.banner.set({ kind: 'err', html: `Save failed: ${e.message ?? e}` });
      },
    });
  }
}

function escape(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
