import { Component, EventEmitter, Input, Output, computed, signal } from '@angular/core';
import { Cta, EnrichedEmail, InboxItem } from '../../core/inbox.types';

interface ActionEntry {
  item: InboxItem;
  enriched: EnrichedEmail;
  cta: Cta;
}

const PANEL_COLLAPSED_KEY = 'intellimailbox-action-panel-collapsed';
const MAX_ROWS = 12;
const PRIORITY_RANK: Record<string, number> = { HIGH: 0, MEDIUM: 1, LOW: 2 };

/**
 * Aggregated Action Items panel — rolls every CTA across the enriched-email
 * set up into one list, sorted overdue → HIGH → MEDIUM → LOW → due date.
 * Mounts above the inbox and digest views.
 *
 * Mirrors legacy renderActionPanel() (src/main/resources/legacy/index.html
 * lines 1857-1936). Click on a row asks the parent to open the email detail
 * modal — the legacy in-place flash / digest-row expansion is not ported,
 * the modal is the natural Angular equivalent.
 */
@Component({
  selector: 'im-action-items-panel',
  standalone: true,
  template: `
    @if (entries().length > 0) {
      <div class="digest-action-panel" [class.action-panel-collapsed]="collapsed()">
        <div class="action-panel-header-row" [class.collapsed]="collapsed()"
             (click)="toggle()">
          <span class="chevron">▾</span>
          <h2>Action Items</h2>
          <span class="panel-summary">{{ summary() }}</span>
        </div>
        <div class="digest-action-list">
          @for (entry of visible(); track $index) {
            <div class="digest-action-row"
                 [class.priority-HIGH]="prioOf(entry) === 'HIGH'"
                 [class.priority-MEDIUM]="prioOf(entry) === 'MEDIUM'"
                 [class.priority-LOW]="prioOf(entry) === 'LOW'"
                 (click)="open.emit(entry.item)">
              <span class="pri-dot"></span>
              <span class="action-text" [title]="entry.cta.text || ''">{{ entry.cta.text }}</span>
              <span class="action-from" [title]="entry.item.sender || ''">{{ entry.item.sender }}</span>
              @if (entry.cta.dueDate) {
                <span class="action-due"
                      [class.overdue]="isOverdue(entry.cta.dueDate)">
                  {{ entry.cta.dueDate }}
                </span>
              } @else {
                <span></span>
              }
            </div>
          }
        </div>
      </div>
    }
  `,
  styles: [`
    :host { display: block; max-width: 1100px; margin: 16px auto 0; padding: 0 24px; }

    .digest-action-panel {
      background: var(--bg-card);
      border: 1px solid var(--border-subtle);
      border-radius: 12px;
      padding: 14px 18px;
      box-shadow: var(--shadow-card);
    }
    .digest-action-panel h2 {
      margin: 0; font-size: 0.85rem; font-weight: 700;
      letter-spacing: 0.06em; text-transform: uppercase; color: var(--accent);
    }

    .action-panel-header-row {
      display: flex; align-items: center; gap: 10px; cursor: pointer;
      user-select: none;
    }
    .action-panel-header-row .chevron {
      font-family: var(--font-mono); font-size: 0.85rem; color: var(--text-muted);
      transition: transform 0.15s;
    }
    .action-panel-header-row.collapsed .chevron { transform: rotate(-90deg); }
    .action-panel-header-row .panel-summary {
      font-size: 0.78rem; color: var(--text-muted); margin-left: auto;
    }
    .action-panel-collapsed .digest-action-list { display: none; }

    .digest-action-list { display: grid; gap: 6px; margin-top: 10px; }
    .digest-action-row {
      display: grid;
      grid-template-columns: auto 1fr auto auto;
      gap: 10px; align-items: center;
      padding: 7px 10px;
      border-radius: 6px;
      border: 1px solid var(--border-subtle);
      background: transparent;
      cursor: pointer;
      transition: background 0.15s, border-color 0.15s;
    }
    .digest-action-row:hover { background: var(--bg-card-hover); border-color: var(--accent); }

    .pri-dot {
      width: 8px; height: 8px; border-radius: 50%;
      background: var(--text-muted);
    }
    .digest-action-row.priority-HIGH   .pri-dot { background: var(--color-error); }
    .digest-action-row.priority-MEDIUM .pri-dot { background: var(--color-warning); }
    .digest-action-row.priority-LOW    .pri-dot { background: var(--color-success); }

    .action-text {
      color: var(--text-primary); line-height: 1.35;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .action-from {
      color: var(--text-muted); font-size: 0.78rem; white-space: nowrap;
      max-width: 220px; overflow: hidden; text-overflow: ellipsis;
    }
    .action-due {
      font-size: 0.7rem; color: var(--text-muted); font-family: var(--font-mono);
      padding: 1px 6px; border-radius: 4px; border: 1px solid var(--border-subtle);
      white-space: nowrap;
    }
    .action-due.overdue { color: var(--color-error); border-color: var(--color-error); }
  `],
})
export class ActionItemsPanelComponent {
  private readonly inputItems = signal<InboxItem[]>([]);
  private readonly inputEnriched = signal<Record<string, EnrichedEmail>>({});

  @Input({ required: true })
  set items(value: InboxItem[]) { this.inputItems.set(value); }
  @Input({ required: true })
  set enrichedMap(value: Record<string, EnrichedEmail>) { this.inputEnriched.set(value); }

  /** Parent opens the email-detail modal for the clicked row. */
  @Output() open = new EventEmitter<InboxItem>();

  readonly collapsed = signal<boolean>(this.loadCollapsed());

  private readonly todayIso = new Date().toISOString().slice(0, 10);

  /** All CTAs across enriched (non-failed) emails, preserving inbox order
   *  for stable iteration before sorting. */
  readonly entries = computed<ActionEntry[]>(() => {
    const enriched = this.inputEnriched();
    const out: ActionEntry[] = [];
    for (const item of this.inputItems()) {
      const e = enriched[item.id];
      if (!e || e.failed) continue;
      for (const cta of e.ctas ?? []) {
        out.push({ item, enriched: e, cta });
      }
    }
    out.sort((a, b) => {
      const aOver = a.cta.dueDate && a.cta.dueDate < this.todayIso ? 0 : 1;
      const bOver = b.cta.dueDate && b.cta.dueDate < this.todayIso ? 0 : 1;
      if (aOver !== bOver) return aOver - bOver;
      const aPri = PRIORITY_RANK[(a.cta.priority || 'MEDIUM').toUpperCase()] ?? 1;
      const bPri = PRIORITY_RANK[(b.cta.priority || 'MEDIUM').toUpperCase()] ?? 1;
      if (aPri !== bPri) return aPri - bPri;
      if (a.cta.dueDate && b.cta.dueDate) return a.cta.dueDate.localeCompare(b.cta.dueDate);
      if (a.cta.dueDate) return -1;
      if (b.cta.dueDate) return 1;
      return 0;
    });
    return out;
  });

  readonly visible = computed<ActionEntry[]>(() => this.entries().slice(0, MAX_ROWS));

  readonly summary = computed<string>(() => {
    const all = this.entries();
    const high = all.filter(x => (x.cta.priority || '').toUpperCase() === 'HIGH').length;
    const overdue = all.filter(x => x.cta.dueDate && x.cta.dueDate < this.todayIso).length;
    const distinct = new Set(all.map(x => x.item.id)).size;
    const bits: string[] = [];
    if (high > 0) bits.push(`${high} high-priority`);
    if (overdue > 0) bits.push(`${overdue} overdue`);
    bits.push(`${all.length} total across ${distinct} email${distinct === 1 ? '' : 's'}`);
    return bits.join(' · ');
  });

  prioOf(e: ActionEntry): 'HIGH' | 'MEDIUM' | 'LOW' {
    const raw = (e.cta.priority || 'MEDIUM').toUpperCase();
    return raw === 'HIGH' || raw === 'LOW' ? raw : 'MEDIUM';
  }

  isOverdue(dueDate: string): boolean {
    return dueDate < this.todayIso;
  }

  toggle(): void {
    const next = !this.collapsed();
    this.collapsed.set(next);
    try { localStorage.setItem(PANEL_COLLAPSED_KEY, next ? '1' : '0'); } catch { /* ignore */ }
  }

  private loadCollapsed(): boolean {
    try { return localStorage.getItem(PANEL_COLLAPSED_KEY) === '1'; } catch { return false; }
  }
}
