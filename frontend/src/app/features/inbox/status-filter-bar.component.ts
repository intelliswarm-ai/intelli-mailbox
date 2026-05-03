import { Component, EventEmitter, Input, Output } from '@angular/core';
import { RowStatus } from '../../core/inbox.types';

export type StatusFilter = 'all' | RowStatus;

interface Pill {
  key: StatusFilter;
  label: string;
  icon: string;
}

const PILLS: Pill[] = [
  { key: 'all',       label: 'All',          icon: '📥' },
  { key: 'analyzing', label: 'Analyzing',    icon: '⌛' },
  { key: 'analyzed',  label: 'Analyzed',     icon: '✓'  },
  { key: 'pending',   label: 'Not analyzed', icon: '📩' },
  { key: 'excluded',  label: 'Excluded',     icon: '🚫' },
  { key: 'failed',    label: 'Failed',       icon: '⚠'  },
];

/**
 * Filter pills above the inbox list. One pill per status bucket. Counts
 * are passed in as a Record so the parent owns the source-of-truth state
 * and this component is purely presentational.
 *
 * Disabled (zero count) pills can't be clicked. The active pill highlights.
 */
@Component({
  selector: 'im-status-filter-bar',
  standalone: true,
  template: `
    <div class="bar">
      @for (p of pills; track p.key) {
        <button type="button"
                class="pill"
                [class.active]="active === p.key"
                [disabled]="counts[p.key] === 0 && p.key !== 'all'"
                (click)="onClick(p.key)">
          <span>{{ p.icon }}</span>
          <span>{{ p.label }}</span>
          <span class="count">{{ counts[p.key] }}</span>
        </button>
      }
    </div>
  `,
  styles: [`
    .bar {
      display: flex; flex-wrap: wrap; gap: 6px;
      padding: 10px 24px;
      background: var(--bg-secondary);
      border-bottom: 1px solid var(--border-section);
    }
    .pill {
      all: unset;
      cursor: pointer;
      display: inline-flex; align-items: center; gap: 6px;
      padding: 4px 12px;
      border-radius: 999px;
      background: var(--bg-input);
      border: 1px solid var(--border-subtle);
      font-size: 0.78rem;
      color: var(--text-secondary);
      transition: border-color 0.15s, color 0.15s, background 0.15s;
    }
    .pill:hover:not(:disabled) {
      border-color: var(--accent);
      color: var(--text-primary);
    }
    .pill.active {
      background: var(--accent-dim);
      color: var(--text-heading);
      border-color: var(--accent);
    }
    .pill .count {
      font-size: 0.74rem;
      color: var(--text-muted);
      margin-left: 2px;
    }
    .pill.active .count { color: var(--accent); }
    .pill:disabled { opacity: 0.4; cursor: default; }
  `],
})
export class StatusFilterBarComponent {
  @Input({ required: true }) active: StatusFilter = 'all';
  @Input({ required: true }) counts: Record<StatusFilter, number> = {
    all: 0, analyzing: 0, analyzed: 0, pending: 0, excluded: 0, failed: 0,
  };
  @Output() filterChange = new EventEmitter<StatusFilter>();

  pills = PILLS;

  onClick(key: StatusFilter): void {
    this.filterChange.emit(key);
  }
}
