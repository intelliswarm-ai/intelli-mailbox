import { Component, EventEmitter, Input, Output } from '@angular/core';
import { EnrichedEmail, InboxItem, RowStatus } from '../../core/inbox.types';

/**
 * One row in the inbox list. Receives plain inputs — Angular's keyed *ngFor
 * (trackBy="id") + OnPush-friendly inputs mean only the row whose enriched
 * data actually changed re-renders, eliminating the legacy blink where the
 * whole list got innerHTML-rewritten on every SSE event.
 *
 * Visual states ported from legacy/index.html (sender · subject · summary +
 * badge chips). Per-row Analyze button + email-detail modal are deferred —
 * they need the email-body endpoint and are a bigger surface than this step.
 */
@Component({
  selector: 'im-inbox-row',
  standalone: true,
  template: `
    <article class="row"
             [attr.data-status]="status"
             [class.clickable]="status === 'analyzed' || status === 'failed'"
             (click)="onRowClick()">
      <div class="head">
        <span class="sender">{{ item.sender }}</span>
        <span class="time">{{ item.time }}</span>
        @if (status === 'pending' || status === 'failed') {
          <button type="button"
                  class="analyze-btn"
                  (click)="analyze.emit(item.id); $event.stopPropagation()"
                  title="Run AI analysis on this single email">🔍 Analyze</button>
        } @else if (status === 'analyzing') {
          <span class="analyzing-tag">⌛ Analyzing…</span>
        }
      </div>
      <div class="subject">{{ item.subject }}</div>
      @if (enriched) {
        <p class="summary" [class.failed]="enriched.failed">{{ enriched.summary }}</p>
        @if (enriched.badges.length) {
          <div class="badges">
            @for (b of enriched.badges; track b) {
              <span class="badge" [attr.data-badge]="b">{{ b.replace('_', ' ') }}</span>
            }
          </div>
        }
        @if (enriched.ctas.length) {
          <ul class="ctas">
            @for (cta of enriched.ctas; track cta.text) {
              <li class="cta" [attr.data-priority]="(cta.priority || 'medium').toLowerCase()">
                <span class="cta-type">{{ (cta.type || 'OTHER').toUpperCase() }}</span>
                <span class="cta-text">{{ cta.text }}</span>
                @if (cta.dueDate) { <span class="cta-due">due {{ cta.dueDate }}</span> }
              </li>
            }
          </ul>
        }
      } @else if (streamingSummary) {
        <p class="summary streaming">{{ streamingSummary }}<span class="caret"></span></p>
      } @else {
        <p class="snippet">{{ item.snippet }}</p>
      }
    </article>
  `,
  styles: [`
    .row {
      background: var(--bg-card);
      border: 1px solid var(--border-subtle);
      border-radius: 12px;
      padding: 14px 18px;
      margin-bottom: 12px;
      box-shadow: var(--shadow-card);
      transition: border-color 0.15s, transform 0.05s;
    }
    .row.clickable { cursor: pointer; }
    .row:hover { border-color: var(--accent); }

    .head {
      display: flex; justify-content: space-between; align-items: baseline;
      gap: 12px; margin-bottom: 4px;
    }
    .sender {
      color: var(--text-heading);
      font-weight: 600;
      font-size: 0.92rem;
    }
    .time { font-size: 0.78rem; color: var(--text-muted); }

    .analyze-btn {
      margin-left: auto;
      background: transparent;
      color: var(--accent);
      border: 1px solid var(--accent);
      padding: 3px 10px;
      border-radius: 6px;
      font-family: inherit;
      font-size: 0.74rem;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .analyze-btn:hover {
      background: var(--accent);
      color: #ffffff;
    }
    .analyzing-tag {
      margin-left: auto;
      font-size: 0.74rem;
      color: var(--color-warning);
    }

    .subject {
      color: var(--text-primary);
      font-size: 0.95rem;
      font-weight: 500;
      margin-bottom: 8px;
    }

    .snippet {
      margin: 0;
      color: var(--text-muted);
      font-size: 0.85rem;
      line-height: 1.45;
    }

    .summary {
      margin: 0;
      color: var(--text-secondary);
      font-size: 0.88rem;
      line-height: 1.5;
    }
    .summary.failed { color: var(--color-error); }
    .summary.streaming .caret {
      display: inline-block;
      width: 7px; height: 1em;
      background: var(--accent);
      vertical-align: text-bottom;
      margin-left: 2px;
      animation: caret-blink 1s steps(1) infinite;
    }
    @keyframes caret-blink {
      50% { opacity: 0; }
    }

    .badges {
      margin-top: 10px;
      display: flex; flex-wrap: wrap; gap: 6px;
    }
    .badge {
      font-size: 0.68rem;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      padding: 2px 8px;
      border-radius: 999px;
      border: 1px solid var(--border-subtle);
      color: var(--text-secondary);
      background: var(--bg-input);
    }
    .badge[data-badge="RISK"]      { color: var(--color-error);   border-color: var(--color-error); }
    .badge[data-badge="VIP"]       { color: var(--accent-2);      border-color: var(--accent-2); }
    .badge[data-badge="MEETING"]   { color: var(--color-info);    border-color: var(--color-info); }
    .badge[data-badge="FINANCE"]   { color: var(--color-warning); border-color: var(--color-warning); }
    .badge[data-badge="FOLLOW_UP"] { color: var(--accent);        border-color: var(--accent); }

    .ctas {
      list-style: none; margin: 10px 0 0; padding: 0;
      display: grid; gap: 4px;
    }
    .cta {
      display: flex; align-items: baseline; gap: 8px;
      font-size: 0.85rem; color: var(--text-secondary);
    }
    .cta::before {
      content: '▸';
      color: var(--accent);
      flex-shrink: 0;
    }
    .cta-type {
      font-size: 0.65rem;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      padding: 1px 6px;
      border-radius: 4px;
      background: var(--accent-dim);
      color: var(--accent);
      flex-shrink: 0;
    }
    .cta[data-priority="high"] .cta-type { background: rgba(255,110,110,0.10); color: var(--color-error); }
    .cta-text { color: var(--text-primary); }
    .cta-due {
      font-size: 0.78rem;
      color: var(--text-muted);
      margin-left: auto;
    }
  `],
})
export class InboxRowComponent {
  @Input({ required: true }) item!: InboxItem;
  @Input() enriched: EnrichedEmail | null = null;
  @Input() streamingSummary: string | null = null;
  @Input() status: RowStatus = 'pending';
  /** User clicked the per-row 🔍 Analyze button — parent fires reprocess(id). */
  @Output() analyze = new EventEmitter<string>();
  /** User clicked the row body — parent opens the email-detail modal.
   *  Only fires when there's enrichment to show (analyzed or failed); pending
   *  and analyzing rows have nothing useful in the modal yet. */
  @Output() open = new EventEmitter<string>();

  onRowClick(): void {
    if (this.status === 'analyzed' || this.status === 'failed') {
      this.open.emit(this.item.id);
    }
  }
}
