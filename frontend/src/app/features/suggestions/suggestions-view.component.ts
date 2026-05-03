import { Component, Input, computed, inject, signal } from '@angular/core';
import { Cta, EnrichedEmail, InboxItem } from '../../core/inbox.types';
import { DraftTone, InboxService } from '../../core/inbox.service';

interface DraftFlash { ok: boolean; text: string; }

interface ReplyEntry  { item: InboxItem; enriched: EnrichedEmail; }
interface DueEntry    { item: InboxItem; enriched: EnrichedEmail; cta: Cta; overdue: boolean; }
interface PhishEntry  { item: InboxItem; enriched: EnrichedEmail; }
interface CleanupEntry { sender: string; entries: { item: InboxItem; enriched: EnrichedEmail }[]; }

const PRIORITY_RANK: Record<string, number> = { HIGH: 0, MEDIUM: 1, LOW: 2 };

function topCtaPriority(e: EnrichedEmail): string {
  const ps = (e.ctas ?? []).map(c => (c.priority || 'MEDIUM').toUpperCase());
  return ps.sort((a, b) => (PRIORITY_RANK[a] ?? 1) - (PRIORITY_RANK[b] ?? 1))[0] ?? 'MEDIUM';
}

function priorityRank(p: string): number {
  return PRIORITY_RANK[(p || 'MEDIUM').toUpperCase()] ?? 1;
}

/**
 * Suggestions view — actionable rollups computed from the enriched-email set.
 * Mirrors legacy renderSuggestions(). Four sections in order:
 *
 *   🎯 Reply now      — emails with needsReply=true and replyDrafts, sorted
 *                        by top-CTA priority (HIGH first)
 *   ⏰ Due today/over  — CTAs with dueDate <= today, sorted by date asc
 *   ⚠ Possible phish  — phishingSuspected=true rows
 *   📬 Unsubscribe    — senders with ≥2 NEWSLETTER-tagged emails
 *
 * The legacy "inject reply draft into Gmail" buttons are not yet ported —
 * they need a BrowserTool inject-text command surface. Drafts are visible
 * inline (collapsed) so the user can copy them out manually for now.
 */
@Component({
  selector: 'im-suggestions-view',
  standalone: true,
  template: `
    @if (totalCount() === 0) {
      @if (anyEnriched()) {
        <div class="empty">
          <h2>You're all caught up</h2>
          <p>
            No replies pending, no overdue actions, nothing flagged as risky,
            no newsletters to unsubscribe from. Nice inbox.
          </p>
        </div>
      } @else {
        <div class="empty">
          <h2>No suggestions yet</h2>
          <p>Suggestions appear once the AI finishes analyzing your inbox.
          Try <b>↻ Refresh inbox</b> if you haven't already.</p>
        </div>
      }
    } @else {
      <div class="suggestions">

        @if (replyNow().length) {
          <section class="section reply">
            <header>
              <h3>🎯 Reply now</h3>
              <span class="sub">
                {{ replyNow().length }} email{{ replyNow().length === 1 ? '' : 's' }} need a response
              </span>
            </header>
            <ul>
              @for (r of replyNow(); track r.item.id) {
                <li class="row">
                  <div class="head">
                    <span class="pill" [attr.data-priority]="topCtaPriority(r.enriched).toLowerCase()">
                      {{ topCtaPriority(r.enriched) }}
                    </span>
                    <span class="sender">{{ r.item.sender || '(unknown)' }}</span>
                    <span class="subject">{{ r.item.subject || '(no subject)' }}</span>
                  </div>
                  <p class="summary">{{ r.enriched.summary }}</p>
                  @if (r.enriched.replyDrafts) {
                    <div class="inject-row">
                      @for (t of tones; track t) {
                        <button type="button"
                                class="inject"
                                [disabled]="injecting() === r.item.id + ':' + t"
                                [class.ok]="draftFlash()[r.item.id + ':' + t]?.ok === true"
                                [class.err]="draftFlash()[r.item.id + ':' + t]?.ok === false"
                                (click)="onInject(r.item.id, t)">
                          @if (draftFlash()[r.item.id + ':' + t]; as f) {
                            {{ f.text }}
                          } @else if (injecting() === r.item.id + ':' + t) {
                            ⌛ Injecting…
                          } @else {
                            {{ toneLabel[t] }}
                          }
                        </button>
                      }
                    </div>
                    <details class="drafts">
                      <summary>Preview drafts</summary>
                      <div class="draft">
                        <span class="draft-label">📩 Formal</span>
                        <pre>{{ r.enriched.replyDrafts.formal }}</pre>
                      </div>
                      <div class="draft">
                        <span class="draft-label">😊 Friendly</span>
                        <pre>{{ r.enriched.replyDrafts.friendly }}</pre>
                      </div>
                      <div class="draft">
                        <span class="draft-label">⚡ Brief</span>
                        <pre>{{ r.enriched.replyDrafts.brief }}</pre>
                      </div>
                    </details>
                  }
                </li>
              }
            </ul>
          </section>
        }

        @if (dueSoon().length) {
          <section class="section due">
            <header>
              <h3>⏰ Due today or overdue</h3>
              <span class="sub">
                {{ dueSoon().length }} action item{{ dueSoon().length === 1 ? '' : 's' }}
              </span>
            </header>
            <ul>
              @for (d of dueSoon(); track $index) {
                <li class="row" [class.overdue]="d.overdue">
                  <div class="head">
                    <span class="pill" [attr.data-priority]="(d.cta.priority || 'medium').toLowerCase()">
                      {{ (d.cta.priority || 'MEDIUM').toUpperCase() }}
                    </span>
                    <span class="due-tag" [class.is-overdue]="d.overdue">
                      {{ d.overdue ? 'OVERDUE' : 'TODAY' }} · {{ d.cta.dueDate }}
                    </span>
                    <span class="sender">{{ d.item.sender || '(unknown)' }}</span>
                  </div>
                  <p class="summary"><b>{{ d.cta.text }}</b></p>
                  @if (d.cta.sourceQuote) {
                    <p class="quote">"{{ d.cta.sourceQuote }}"</p>
                  }
                </li>
              }
            </ul>
          </section>
        }

        @if (phishing().length) {
          <section class="section phishing">
            <header>
              <h3>⚠ Possible phishing</h3>
              <span class="sub">
                {{ phishing().length }} email{{ phishing().length === 1 ? '' : 's' }} flagged for review
              </span>
            </header>
            <ul>
              @for (p of phishing(); track p.item.id) {
                <li class="row is-phishing">
                  <div class="head">
                    <span class="pill warn">⚠ PHISHING</span>
                    <span class="sender">{{ p.item.sender || '(unknown)' }}</span>
                    <span class="subject">{{ p.item.subject || '(no subject)' }}</span>
                  </div>
                  <p class="summary">{{ p.enriched.summary }}</p>
                </li>
              }
            </ul>
          </section>
        }

        @if (cleanup().length) {
          <section class="section cleanup">
            <header>
              <h3>📬 Unsubscribe candidates</h3>
              <span class="sub">
                {{ cleanup().length }} sender{{ cleanup().length === 1 ? '' : 's' }} with ≥2 newsletters
              </span>
            </header>
            <ul>
              @for (c of cleanup(); track c.sender) {
                <li class="row">
                  <div class="head">
                    <span class="pill">{{ c.entries.length }} newsletters</span>
                    <span class="sender">{{ c.sender }}</span>
                  </div>
                  <p class="summary">
                    @for (e of c.entries.slice(0, 3); track e.item.id) {
                      · {{ e.item.subject || '(no subject)' }}<br>
                    }
                    @if (c.entries.length > 3) {
                      … and {{ c.entries.length - 3 }} more
                    }
                  </p>
                </li>
              }
            </ul>
          </section>
        }

      </div>
    }
  `,
  styles: [`
    .empty {
      max-width: 720px;
      margin: 60px auto;
      padding: 0 24px;
      text-align: center;
      color: var(--text-muted);
    }
    .empty h2 { color: var(--text-heading); font-weight: 600; margin: 0 0 12px; }

    .suggestions {
      max-width: 1100px;
      margin: 20px auto;
      padding: 0 24px;
    }
    .section { margin-bottom: 28px; }
    .section header {
      display: flex; align-items: baseline; gap: 12px;
      padding-bottom: 6px; margin-bottom: 10px;
      border-bottom: 1px solid var(--border-subtle);
    }
    .section h3 {
      margin: 0;
      font-size: 0.95rem;
      font-weight: 600;
      color: var(--text-heading);
    }
    .section .sub { color: var(--text-muted); font-size: 0.78rem; }

    ul { list-style: none; margin: 0; padding: 0; }

    .row {
      background: var(--bg-card);
      border: 1px solid var(--border-subtle);
      border-radius: 10px;
      padding: 12px 16px;
      margin-bottom: 8px;
    }
    .row.overdue { border-left: 3px solid var(--color-error); }
    .row.is-phishing {
      border-color: var(--color-error);
      background: rgba(255, 110, 110, 0.06);
    }

    .head {
      display: flex; flex-wrap: wrap; gap: 8px; align-items: center;
      margin-bottom: 6px;
    }
    .sender { color: var(--text-heading); font-weight: 600; font-size: 0.88rem; }
    .subject { color: var(--text-secondary); font-size: 0.88rem; }

    .pill {
      font-size: 0.65rem;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      padding: 2px 8px;
      border-radius: 4px;
      background: var(--accent-dim);
      color: var(--accent);
      flex-shrink: 0;
    }
    .pill[data-priority="high"]   { background: rgba(255,110,110,0.10); color: var(--color-error); }
    .pill[data-priority="medium"] { background: var(--accent-dim);      color: var(--accent); }
    .pill[data-priority="low"]    { background: rgba(166,166,201,0.10); color: var(--text-muted); }
    .pill.warn                    { background: rgba(255,110,110,0.10); color: var(--color-error); }

    .due-tag {
      font-size: 0.72rem;
      color: var(--color-warning);
      font-weight: 500;
    }
    .due-tag.is-overdue { color: var(--color-error); }

    .summary {
      margin: 0;
      color: var(--text-secondary);
      font-size: 0.88rem;
      line-height: 1.5;
    }
    .quote {
      margin: 4px 0 0;
      color: var(--text-muted);
      font-style: italic;
      font-size: 0.82rem;
    }

    .inject-row {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
      margin-top: 10px;
    }
    .inject {
      padding: 6px 12px;
      border-radius: 8px;
      background: var(--accent-dim);
      color: var(--accent);
      border: 1px solid var(--accent);
      font-family: inherit;
      font-size: 0.78rem;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .inject:hover:not(:disabled) {
      background: var(--gradient-brand);
      color: #ffffff;
      border-color: var(--accent-2);
    }
    .inject:disabled { opacity: 0.5; cursor: not-allowed; }
    .inject.ok {
      background: rgba(102,187,106,0.10);
      color: var(--color-success);
      border-color: var(--color-success);
      opacity: 1;
    }
    .inject.err {
      background: rgba(255,110,110,0.10);
      color: var(--color-error);
      border-color: var(--color-error);
      opacity: 1;
    }

    .drafts {
      margin-top: 10px;
      padding: 8px 10px;
      background: var(--bg-input);
      border-radius: 6px;
    }
    .drafts summary {
      cursor: pointer;
      font-size: 0.82rem;
      color: var(--accent);
      list-style: none;
    }
    .drafts summary::-webkit-details-marker { display: none; }
    .draft { margin-top: 8px; }
    .draft-label {
      font-size: 0.72rem;
      color: var(--text-muted);
      letter-spacing: 0.04em;
    }
    .draft pre {
      margin: 4px 0 0;
      padding: 8px 10px;
      background: var(--bg-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 6px;
      color: var(--text-primary);
      font-family: var(--font-sans);
      font-size: 0.85rem;
      line-height: 1.5;
      white-space: pre-wrap;
    }
  `],
})
export class SuggestionsViewComponent {
  private readonly inputItems = signal<InboxItem[]>([]);
  private readonly inputEnriched = signal<Record<string, EnrichedEmail>>({});
  private inboxService = inject(InboxService);

  @Input({ required: true })
  set items(value: InboxItem[]) { this.inputItems.set(value); }
  @Input({ required: true })
  set enrichedMap(value: Record<string, EnrichedEmail>) { this.inputEnriched.set(value); }

  readonly topCtaPriority = topCtaPriority;
  readonly tones: DraftTone[] = ['formal', 'friendly', 'brief'];
  readonly toneLabel: Record<DraftTone, string> = {
    formal: '📩 Formal',
    friendly: '😊 Friendly',
    brief: '⚡ Brief',
  };

  /** Key shape: `${id}:${tone}` so multiple drafts on different rows can flash
   *  independently without clobbering each other. Partial<> so TS treats
   *  absent keys as `undefined` rather than as the value type. */
  readonly draftFlash = signal<Partial<Record<string, DraftFlash>>>({});
  readonly injecting = signal<string | null>(null);

  onInject(id: string, tone: DraftTone): void {
    const key = `${id}:${tone}`;
    this.injecting.set(key);
    this.inboxService.injectDraft(id, tone).subscribe({
      next: (res) => {
        this.injecting.set(null);
        this.flash(key, res.ok, res.ok ? '✓ Pasted in Gmail' : `✗ ${res.reason || 'Failed'}`);
      },
      error: (e) => {
        this.injecting.set(null);
        this.flash(key, false, `✗ ${e.message ?? 'Failed'}`);
      },
    });
  }

  private flash(key: string, ok: boolean, text: string): void {
    this.draftFlash.set({ ...this.draftFlash(), [key]: { ok, text } });
    setTimeout(() => {
      const next = { ...this.draftFlash() };
      delete next[key];
      this.draftFlash.set(next);
    }, 2000);
  }

  /** All enriched (and not failed) email rows, with their inbox metadata. */
  private readonly enrichedRows = computed(() => {
    const enriched = this.inputEnriched();
    const out: { item: InboxItem; enriched: EnrichedEmail }[] = [];
    for (const item of this.inputItems()) {
      const e = enriched[item.id];
      if (!e || e.failed) continue;
      out.push({ item, enriched: e });
    }
    return out;
  });

  readonly anyEnriched = computed(() => this.enrichedRows().length > 0);

  readonly replyNow = computed<ReplyEntry[]>(() => {
    return this.enrichedRows()
      .filter(r => r.enriched.needsReply && r.enriched.replyDrafts)
      .sort((a, b) =>
        priorityRank(topCtaPriority(a.enriched)) - priorityRank(topCtaPriority(b.enriched)));
  });

  readonly dueSoon = computed<DueEntry[]>(() => {
    const todayIso = new Date().toISOString().slice(0, 10);
    const out: DueEntry[] = [];
    for (const { item, enriched } of this.enrichedRows()) {
      for (const cta of enriched.ctas ?? []) {
        if (!cta.dueDate) continue;
        if (cta.dueDate <= todayIso) {
          out.push({ item, enriched, cta, overdue: cta.dueDate < todayIso });
        }
      }
    }
    out.sort((a, b) => (a.cta.dueDate || '').localeCompare(b.cta.dueDate || ''));
    return out;
  });

  readonly phishing = computed<PhishEntry[]>(() => {
    return this.enrichedRows().filter(r => r.enriched.phishingSuspected);
  });

  readonly cleanup = computed<CleanupEntry[]>(() => {
    const bySender = new Map<string, { item: InboxItem; enriched: EnrichedEmail }[]>();
    for (const r of this.enrichedRows()) {
      if (!(r.enriched.badges ?? []).includes('NEWSLETTER')) continue;
      const sender = r.item.sender || '(unknown)';
      if (!bySender.has(sender)) bySender.set(sender, []);
      bySender.get(sender)!.push(r);
    }
    const out: CleanupEntry[] = [];
    for (const [sender, entries] of bySender.entries()) {
      if (entries.length >= 2) out.push({ sender, entries });
    }
    out.sort((a, b) => b.entries.length - a.entries.length);
    return out;
  });

  readonly totalCount = computed(() =>
    this.replyNow().length + this.dueSoon().length + this.phishing().length + this.cleanup().length);
}
