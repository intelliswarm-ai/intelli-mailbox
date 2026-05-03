import {
  Component, EventEmitter, Input, OnInit, Output, computed, inject, signal,
} from '@angular/core';
import { ExcludedSendersService } from '../../core/excluded-senders.service';
import { EnrichedEmail, InboxItem } from '../../core/inbox.types';

interface SenderGroup {
  key: string;       // lowercased sender, used for matching + dedup
  display: string;   // original-cased sender for display
  items: InboxItem[];
  excluded: boolean;
  allEnriched: boolean;
  someEnriched: boolean;
  checked: boolean;
}

/**
 * "Process all" sender picker. Replaces the legacy openProcessPicker() —
 * lists each unique sender in the current inbox with a checkbox so the user
 * can opt out per-batch (no LLM cost for senders they don't care about right
 * now), plus a 🚫 button that adds the sender to the permanent excluded list
 * (POST /api/excluded-senders).
 *
 * Default-checked logic ported faithfully:
 *   • unchecked when the sender is on the permanent exclude list
 *   • unchecked when every email from this sender is already analyzed (no
 *     point re-paying)
 *   • checked otherwise
 *
 * The component does NOT call /api/inbox/process — it emits the selected
 * email ids on confirm and the parent fires per-id /reprocess in parallel,
 * which matches the legacy behavior (lets backend serialize via the single
 * enricher thread).
 */
@Component({
  selector: 'im-process-picker',
  standalone: true,
  template: `
    <div class="backdrop" (click)="onCancel()">
      <div class="card" (click)="$event.stopPropagation()">

        <header class="head">
          <h2>Choose senders to process</h2>
          <span class="meta">{{ metaText() }}</span>
        </header>

        <div class="toolbar">
          <button type="button" class="secondary" (click)="selectAll()">Select all</button>
          <button type="button" class="secondary" (click)="selectNone()">Select none</button>
          <span class="tip">
            Tip: 🚫 button adds them to the permanent excluded list (saved on disk).
          </span>
        </div>

        <div class="body">
          @for (g of groups(); track g.key) {
            <label class="row">
              <input type="checkbox"
                     [checked]="g.checked"
                     (change)="toggleSender(g.key, $any($event.target).checked)">
              <span class="sender" [title]="g.display">{{ g.display }}</span>
              <span class="count">
                {{ g.items.length }} email{{ g.items.length === 1 ? '' : 's' }}
                @if (g.excluded) { · permanently excluded }
                @else if (g.allEnriched) { · already analyzed }
              </span>
              <button type="button"
                      class="exclude-btn secondary"
                      [disabled]="g.excluded || excluding() === g.key"
                      (click)="onExcludeClick($event, g)"
                      [title]="g.excluded
                        ? 'Already on the permanent excluded list'
                        : 'Add this sender to the permanent excluded list (saved to disk)'">
                @if (g.excluded) { 🚫 excluded }
                @else if (excluding() === g.key) { 🚫 …excluding }
                @else { 🚫 Exclude }
              </button>
            </label>
          }
          @if (groups().length === 0) {
            <p class="empty">Nothing to process — refresh the inbox first.</p>
          }
        </div>

        <footer class="foot">
          <button type="button" class="secondary" (click)="onCancel()">Cancel</button>
          <button type="button"
                  [disabled]="totalSelected() === 0"
                  (click)="onConfirm()">
            Process selected
          </button>
        </footer>
      </div>
    </div>
  `,
  styles: [`
    .backdrop {
      position: fixed; inset: 0; z-index: 100;
      background: rgba(0, 0, 0, 0.55);
      display: flex; align-items: center; justify-content: center;
      animation: fadein 0.15s ease;
    }
    @keyframes fadein { from { opacity: 0; } to { opacity: 1; } }

    .card {
      background: var(--bg-modal);
      border: 1px solid var(--border-subtle);
      border-radius: 12px;
      width: min(720px, 92vw);
      max-height: 90vh;
      display: flex; flex-direction: column;
      color: var(--text-primary);
      box-shadow: 0 20px 60px rgba(0, 0, 0, 0.4);
      overflow: hidden;
    }

    .head {
      display: flex; justify-content: space-between; align-items: baseline;
      padding: 16px 20px;
      border-bottom: 1px solid var(--border-subtle);
    }
    .head h2 { margin: 0; font-size: 1rem; font-weight: 600; color: var(--text-heading); }
    .meta { font-size: 0.78rem; color: var(--text-muted); }

    .toolbar {
      display: flex; gap: 8px; align-items: center;
      padding: 8px 20px;
      border-bottom: 1px solid var(--border-subtle);
    }
    .toolbar .tip { margin-left: auto; color: var(--text-muted); font-size: 0.78rem; }
    .toolbar button {
      padding: 5px 12px;
      border-radius: 8px;
      background: transparent;
      color: var(--text-secondary);
      border: 1px solid var(--border-subtle);
      cursor: pointer;
      font-size: 0.82rem;
      font-family: inherit;
    }
    .toolbar button:hover { color: var(--text-heading); border-color: var(--accent); }

    .body {
      padding: 4px 20px;
      overflow-y: auto;
      flex: 1;
    }
    .row {
      display: grid;
      grid-template-columns: 24px 1fr auto auto;
      gap: 12px;
      align-items: center;
      padding: 10px 0;
      border-bottom: 1px solid var(--border-subtle);
      font-size: 0.88rem;
    }
    .row:last-child { border-bottom: 0; }
    .row input[type="checkbox"] { cursor: pointer; }
    .sender {
      color: var(--text-primary);
      word-break: break-all;
      cursor: pointer;
    }
    .count {
      font-size: 0.78rem;
      color: var(--text-muted);
      white-space: nowrap;
    }
    .exclude-btn {
      padding: 4px 10px;
      border-radius: 6px;
      background: transparent;
      color: var(--text-secondary);
      border: 1px solid var(--border-subtle);
      cursor: pointer;
      font-size: 0.78rem;
      font-family: inherit;
    }
    .exclude-btn:hover:not(:disabled) {
      color: var(--color-error);
      border-color: var(--color-error);
    }
    .exclude-btn:disabled { opacity: 0.5; cursor: not-allowed; }

    .empty { padding: 40px 0; text-align: center; color: var(--text-muted); }

    .foot {
      display: flex; gap: 8px; justify-content: flex-end;
      padding: 12px 20px;
      border-top: 1px solid var(--border-subtle);
    }
    .foot button {
      padding: 7px 16px;
      border-radius: 8px;
      font-family: inherit;
      font-size: 0.85rem;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .foot button.secondary {
      background: transparent;
      color: var(--text-secondary);
      border: 1px solid var(--border-subtle);
    }
    .foot button.secondary:hover {
      color: var(--text-heading);
      border-color: var(--accent);
      background: var(--bg-card-hover);
    }
    .foot button:not(.secondary) {
      background: var(--accent-dim);
      color: var(--accent);
      border: 1px solid var(--accent);
    }
    .foot button:not(.secondary):hover:not(:disabled) {
      background: var(--gradient-brand);
      color: #ffffff;
      border-color: var(--accent-2);
    }
    .foot button:disabled { opacity: 0.5; cursor: not-allowed; }
  `],
})
export class ProcessPickerComponent implements OnInit {
  @Input({ required: true }) items: InboxItem[] = [];
  @Input({ required: true }) enriched: Record<string, EnrichedEmail> = {};
  /** Emits list of email ids the user wants processed (under checked senders). */
  @Output() confirmed = new EventEmitter<string[]>();
  @Output() cancelled = new EventEmitter<void>();

  private excludedService = inject(ExcludedSendersService);

  /** Sender key → user's current checkbox state. Lazily initialized from defaults. */
  private readonly checkedKeys = signal<Record<string, boolean>>({});
  /** Set to the sender key during an in-flight POST /api/excluded-senders. */
  readonly excluding = signal<string | null>(null);

  readonly groups = computed<SenderGroup[]>(() => {
    const map = new Map<string, { display: string; items: InboxItem[] }>();
    for (const it of this.items) {
      const display = it.sender || '(unknown)';
      const key = display.trim().toLowerCase();
      if (!map.has(key)) map.set(key, { display, items: [] });
      map.get(key)!.items.push(it);
    }
    const excluded = this.excludedService.all();
    const checks = this.checkedKeys();
    const groups: SenderGroup[] = [];
    for (const [key, { display, items }] of map.entries()) {
      const allEnriched = items.every(i => !!this.enriched[i.id]);
      const someEnriched = items.some(i => !!this.enriched[i.id]);
      const isExcluded = excluded.has(key);
      // Per-render default; user toggles override via checkedKeys.
      const defaultChecked = !isExcluded && !allEnriched;
      const checked = checks[key] ?? defaultChecked;
      groups.push({ key, display, items, excluded: isExcluded, allEnriched, someEnriched, checked });
    }
    // Sort: noisiest senders first (most emails), then alphabetical by display.
    return groups.sort((a, b) =>
      b.items.length - a.items.length || a.display.localeCompare(b.display));
  });

  readonly totalSelected = computed(() => {
    return this.groups()
      .filter(g => g.checked)
      .reduce((sum, g) => sum + g.items.length, 0);
  });

  readonly metaText = computed(() => {
    const all = this.groups();
    const sel = all.filter(g => g.checked);
    let fresh = 0, reanalyze = 0;
    for (const g of sel) {
      for (const it of g.items) {
        if (this.enriched[it.id]) reanalyze++;
        else fresh++;
      }
    }
    const total = fresh + reanalyze;
    const note = reanalyze > 0 ? ` (${reanalyze} will re-analyze)` : '';
    return `${sel.length} of ${all.length} senders · ${total} email${total === 1 ? '' : 's'}${note}`;
  });

  ngOnInit(): void {
    // Refresh the excluded-sender set so defaults are correct on first paint.
    this.excludedService.refresh().subscribe({ error: () => { /* keep stale set */ } });
  }

  toggleSender(key: string, checked: boolean): void {
    this.checkedKeys.set({ ...this.checkedKeys(), [key]: checked });
  }

  selectAll(): void {
    const next: Record<string, boolean> = {};
    for (const g of this.groups()) next[g.key] = true;
    this.checkedKeys.set(next);
  }

  selectNone(): void {
    const next: Record<string, boolean> = {};
    for (const g of this.groups()) next[g.key] = false;
    this.checkedKeys.set(next);
  }

  onExcludeClick(ev: Event, g: SenderGroup): void {
    ev.preventDefault();
    if (g.excluded) return;
    this.excluding.set(g.key);
    this.excludedService.add(g.display).subscribe({
      next: () => {
        this.excluding.set(null);
        // Uncheck this row — exclude implies "don't process".
        this.toggleSender(g.key, false);
      },
      error: () => {
        this.excluding.set(null);
      },
    });
  }

  onConfirm(): void {
    const ids: string[] = [];
    for (const g of this.groups()) {
      if (!g.checked) continue;
      for (const it of g.items) ids.push(it.id);
    }
    this.confirmed.emit(ids);
  }

  onCancel(): void {
    this.cancelled.emit();
  }
}
