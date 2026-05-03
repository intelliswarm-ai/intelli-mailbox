import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ExcludedSendersService } from '../../core/excluded-senders.service';

/**
 * Excluded-senders sub-panel inside the Settings modal. Lists the persistent
 * "do not process" sender list with per-row Remove buttons + an Add field.
 *
 * Reuses the shared ExcludedSendersService — its signal is the source of
 * truth so the process picker, the row status pill, and this panel all
 * stay in sync without ad-hoc broadcasting.
 */
@Component({
  selector: 'im-excluded-senders-panel',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="panel">
      <header>
        <h4>🚫 Excluded senders</h4>
        <span class="hint">
          Add senders to skip — they'll never be sent to the AI. The process picker
          also exposes a one-click 🚫 Exclude on each row.
        </span>
      </header>

      @if (loading()) {
        <div class="empty">Loading…</div>
      } @else if (errorMsg()) {
        <div class="empty err">Couldn't load list: {{ errorMsg() }}</div>
      } @else if (senders().length === 0) {
        <div class="empty">No excluded senders yet.</div>
      } @else {
        <ul class="list">
          @for (s of senders(); track s) {
            <li>
              <span class="text">{{ s }}</span>
              <button type="button"
                      class="rm"
                      [disabled]="busy() === s"
                      (click)="onRemove(s)"
                      title="Remove from excluded list — this sender will be processed again on next refresh">
                {{ busy() === s ? '…' : 'Remove' }}
              </button>
            </li>
          }
        </ul>
      }

      <div class="add-row">
        <input type="text"
               [(ngModel)]="draft"
               (keydown.enter)="onAdd()"
               placeholder="Add sender (paste from an inbox row)…"
               autocomplete="off">
        <button type="button"
                [disabled]="!draft.trim() || busy() === '__add__'"
                (click)="onAdd()">
          {{ busy() === '__add__' ? 'Adding…' : 'Add' }}
        </button>
      </div>
    </section>
  `,
  styles: [`
    .panel {
      margin-top: 18px;
      padding-top: 14px;
      border-top: 1px solid var(--border-subtle);
    }
    header {
      display: flex; flex-direction: column; gap: 2px;
      margin-bottom: 10px;
    }
    h4 {
      margin: 0;
      font-size: 0.82rem;
      font-weight: 600;
      color: var(--text-secondary);
      letter-spacing: 0.02em;
    }
    .hint {
      font-size: 0.78rem;
      color: var(--text-muted);
      line-height: 1.5;
    }

    .empty {
      padding: 14px;
      text-align: center;
      color: var(--text-muted);
      font-size: 0.85rem;
      background: var(--bg-input);
      border-radius: 8px;
    }
    .empty.err { color: var(--color-error); }

    .list {
      list-style: none;
      margin: 0 0 10px;
      padding: 0;
      max-height: 220px;
      overflow-y: auto;
      border: 1px solid var(--border-subtle);
      border-radius: 8px;
    }
    .list li {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 8px 12px;
      border-bottom: 1px solid var(--border-subtle);
    }
    .list li:last-child { border-bottom: 0; }
    .text {
      flex: 1;
      word-break: break-all;
      font-size: 0.85rem;
      color: var(--text-primary);
    }
    .rm {
      background: transparent;
      color: var(--text-secondary);
      border: 1px solid var(--border-subtle);
      padding: 3px 10px;
      border-radius: 6px;
      font-family: inherit;
      font-size: 0.75rem;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .rm:hover:not(:disabled) {
      color: var(--color-error);
      border-color: var(--color-error);
    }
    .rm:disabled { opacity: 0.5; cursor: not-allowed; }

    .add-row {
      display: flex;
      gap: 6px;
    }
    .add-row input {
      flex: 1;
      background: var(--bg-input);
      border: 1px solid var(--border-subtle);
      border-radius: 8px;
      padding: 7px 10px;
      color: var(--text-primary);
      font-family: inherit;
      font-size: 0.85rem;
    }
    .add-row input:focus { outline: none; border-color: var(--accent); }
    .add-row button {
      background: var(--accent-dim);
      color: var(--accent);
      border: 1px solid var(--accent);
      padding: 7px 14px;
      border-radius: 8px;
      font-family: inherit;
      font-size: 0.82rem;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .add-row button:hover:not(:disabled) {
      background: var(--gradient-brand);
      color: #ffffff;
      border-color: var(--accent-2);
    }
    .add-row button:disabled { opacity: 0.5; cursor: not-allowed; }
  `],
})
export class ExcludedSendersPanelComponent implements OnInit {
  private excludedService = inject(ExcludedSendersService);

  readonly loading = signal<boolean>(true);
  readonly errorMsg = signal<string | null>(null);
  /** When non-null, identifies the in-flight operation:
   *   '__add__'  → POST adding a new sender
   *   '<sender>' → DELETE removing this sender */
  readonly busy = signal<string | null>(null);

  /** Reads the live Set<string> from the shared service and projects it as
   *  a sorted display array. The service's set holds lowercased keys; for
   *  display we just render them as-is (server already preserves case). */
  readonly senders = computed<string[]>(() => {
    return [...this.excludedService.all()].sort();
  });

  draft = '';

  ngOnInit(): void {
    this.excludedService.refresh().subscribe({
      next: () => this.loading.set(false),
      error: (e) => {
        this.loading.set(false);
        this.errorMsg.set(e?.message ?? String(e));
      },
    });
  }

  onAdd(): void {
    const v = this.draft.trim();
    if (!v) return;
    this.busy.set('__add__');
    this.excludedService.add(v).subscribe({
      next: () => {
        this.busy.set(null);
        this.draft = '';
      },
      error: (e) => {
        this.busy.set(null);
        this.errorMsg.set(e?.message ?? String(e));
      },
    });
  }

  onRemove(sender: string): void {
    this.busy.set(sender);
    this.excludedService.remove(sender).subscribe({
      next: () => this.busy.set(null),
      error: (e) => {
        this.busy.set(null);
        this.errorMsg.set(e?.message ?? String(e));
      },
    });
  }
}
