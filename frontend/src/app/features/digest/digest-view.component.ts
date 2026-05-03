import { Component, Input, computed, signal } from '@angular/core';
import { Badge, EnrichedEmail } from '../../core/inbox.types';

/** Order ported from legacy: RISK first so phishing / security threats lead;
 *  then people-tied signals (VIP, follow-ups, meetings); then transactional. */
const DIGEST_ORDER: Badge[] = [
  'RISK', 'VIP', 'FOLLOW_UP', 'MEETING', 'FINANCE', 'EXTERNAL', 'AUTOMATED', 'NEWSLETTER',
];

const BADGE_LABEL: Record<Badge | 'UNCATEGORIZED', string> = {
  RISK:          '⚠ Risk / phishing',
  VIP:           '★ VIP',
  FOLLOW_UP:     '↻ Follow-up',
  MEETING:       '📅 Meetings',
  FINANCE:       '💳 Finance',
  EXTERNAL:      '🔗 External',
  AUTOMATED:     '🤖 Automated',
  NEWSLETTER:    '📰 Newsletters',
  UNCATEGORIZED: '· Uncategorized',
};

interface Section {
  key: Badge | 'UNCATEGORIZED';
  label: string;
  entries: EnrichedEmail[];
}

/**
 * Digest view — emails grouped by badge category (DIGEST_ORDER), with each
 * email landing in the FIRST matching badge so a row tagged both RISK and
 * EXTERNAL appears under RISK only. Mirrors legacy renderDigest().
 */
@Component({
  selector: 'im-digest-view',
  standalone: true,
  template: `
    @if (sections().length) {
      <div class="digest">
      @for (section of sections(); track section.key) {
        <section class="section" [attr.data-badge]="section.key">
          <h3>
            <span class="label">{{ section.label }}</span>
            <span class="count">{{ section.entries.length }}</span>
          </h3>
          <ul>
            @for (e of section.entries; track e.item.id) {
              <li class="entry" [class.failed]="e.failed">
                <div class="head">
                  <span class="sender">{{ e.item.sender }}</span>
                  <span class="time">{{ e.item.time }}</span>
                </div>
                <div class="subject">{{ e.item.subject }}</div>
                <p class="summary">{{ e.summary }}</p>
                @if (e.ctas.length) {
                  <ul class="ctas">
                    @for (cta of e.ctas; track cta.text) {
                      <li class="cta" [attr.data-priority]="(cta.priority || 'medium').toLowerCase()">
                        <span class="cta-type">{{ (cta.type || 'OTHER').toUpperCase() }}</span>
                        <span>{{ cta.text }}</span>
                        @if (cta.dueDate) { <span class="cta-due">due {{ cta.dueDate }}</span> }
                      </li>
                    }
                  </ul>
                }
              </li>
            }
          </ul>
        </section>
      }
      </div>
    } @else {
      <div class="empty">
        No analyzed emails yet — click <b>▶ Process all</b> in the toolbar to start.
      </div>
    }
  `,
  styles: [`
    .digest {
      max-width: 1100px;
      margin: 20px auto;
      padding: 0 24px;
    }
    .section {
      margin-bottom: 28px;
    }
    .section h3 {
      display: flex; align-items: center; gap: 8px;
      margin: 0 0 10px;
      font-size: 0.95rem;
      font-weight: 600;
      color: var(--text-heading);
      letter-spacing: 0.02em;
      padding-bottom: 6px;
      border-bottom: 1px solid var(--border-subtle);
    }
    .section h3 .count {
      font-size: 0.75rem;
      color: var(--text-muted);
      font-weight: 500;
    }
    .section[data-badge="RISK"] h3 .label { color: var(--color-error); }
    .section[data-badge="VIP"] h3 .label { color: var(--accent-2); }

    ul { list-style: none; margin: 0; padding: 0; }

    .entry {
      background: var(--bg-card);
      border: 1px solid var(--border-subtle);
      border-radius: 10px;
      padding: 12px 16px;
      margin-bottom: 8px;
    }
    .entry.failed { border-color: var(--color-error); }
    .head {
      display: flex; justify-content: space-between; align-items: baseline;
      gap: 12px; margin-bottom: 4px;
    }
    .sender { color: var(--text-heading); font-weight: 600; font-size: 0.88rem; }
    .time { font-size: 0.74rem; color: var(--text-muted); }
    .subject { color: var(--text-primary); font-size: 0.92rem; margin-bottom: 6px; }
    .summary {
      margin: 0;
      color: var(--text-secondary);
      font-size: 0.85rem; line-height: 1.5;
    }
    .ctas {
      display: grid; gap: 4px;
      margin-top: 8px;
    }
    .cta {
      display: flex; align-items: baseline; gap: 8px;
      font-size: 0.82rem;
      color: var(--text-secondary);
    }
    .cta::before { content: '▸'; color: var(--accent); }
    .cta-type {
      font-size: 0.62rem;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      padding: 1px 6px;
      border-radius: 4px;
      background: var(--accent-dim);
      color: var(--accent);
    }
    .cta[data-priority="high"] .cta-type {
      background: rgba(255,110,110,0.10);
      color: var(--color-error);
    }
    .cta-due {
      font-size: 0.74rem;
      color: var(--text-muted);
      margin-left: auto;
    }

    .empty {
      max-width: 720px;
      margin: 60px auto;
      padding: 0 24px;
      text-align: center;
      color: var(--text-muted);
    }
  `],
})
export class DigestViewComponent {
  private readonly inputEnriched = signal<EnrichedEmail[]>([]);

  @Input({ required: true })
  set enriched(value: EnrichedEmail[]) {
    this.inputEnriched.set(value);
  }

  readonly sections = computed<Section[]>(() => {
    const buckets: Record<string, EnrichedEmail[]> = {};
    DIGEST_ORDER.forEach((b) => { buckets[b] = []; });
    const uncategorized: EnrichedEmail[] = [];
    for (const e of this.inputEnriched()) {
      const target = DIGEST_ORDER.find((b) => e.badges.includes(b));
      if (target) buckets[target].push(e);
      else uncategorized.push(e);
    }
    const out: Section[] = [];
    for (const k of DIGEST_ORDER) {
      if (buckets[k].length > 0) {
        out.push({ key: k, label: BADGE_LABEL[k], entries: buckets[k] });
      }
    }
    if (uncategorized.length > 0) {
      out.push({ key: 'UNCATEGORIZED', label: BADGE_LABEL.UNCATEGORIZED, entries: uncategorized });
    }
    return out;
  });
}
