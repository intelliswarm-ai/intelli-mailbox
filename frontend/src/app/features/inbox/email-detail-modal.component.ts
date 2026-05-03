import {
  Component, EventEmitter, HostListener, Input, OnChanges, Output, SimpleChanges,
  inject, signal,
} from '@angular/core';
import { InboxService, DraftTone } from '../../core/inbox.service';
import { EnrichedEmail, InboxItem } from '../../core/inbox.types';

interface DraftFlash { ok: boolean; text: string; }

/**
 * Email detail modal. Loads /api/email/{id}/details for the cached enrichment
 * + raw body. Renders the enrichment header (summary, badges, CTAs) and the
 * scraped body in a scrollable `<pre>` so the user can see exactly what the
 * AI processed.
 *
 * If `needsReply` and `replyDrafts` are present, the footer offers three
 * inject buttons (formal / friendly / brief) that POST to /inject-draft —
 * the server pastes the chosen draft into Gmail's compose box; the user
 * reviews and sends manually.
 */
@Component({
  selector: 'im-email-detail-modal',
  standalone: true,
  template: `
    <div class="backdrop" (click)="close()">
      <div class="card" (click)="$event.stopPropagation()">

        <header class="head">
          <div class="meta">
            <div class="sender">{{ item.sender }}</div>
            <div class="subject">{{ item.subject }}</div>
            <div class="time">{{ item.time }}</div>
          </div>
          <button type="button" class="close-btn" (click)="close()" aria-label="Close">✕</button>
        </header>

        @if (enriched(); as e) {
          <div class="enrichment">
            <p class="summary" [class.failed]="e.failed">{{ e.summary }}</p>
            @if (e.badges.length) {
              <div class="badges">
                @for (b of e.badges; track b) {
                  <span class="badge" [attr.data-badge]="b">{{ b.replace('_', ' ') }}</span>
                }
              </div>
            }
            @if (e.ctas.length) {
              <ul class="ctas">
                @for (cta of e.ctas; track cta.text) {
                  <li class="cta" [attr.data-priority]="(cta.priority || 'medium').toLowerCase()">
                    <span class="cta-type">{{ (cta.type || 'OTHER').toUpperCase() }}</span>
                    <span>{{ cta.text }}</span>
                    @if (cta.dueDate) { <span class="cta-due">due {{ cta.dueDate }}</span> }
                    @if (cta.sourceQuote) {
                      <p class="quote">"{{ cta.sourceQuote }}"</p>
                    }
                  </li>
                }
              </ul>
            }
          </div>

          <div class="body-wrap">
            <h4>Email body</h4>
            @if (body()) {
              <pre class="body">{{ body() }}</pre>
            } @else {
              <p class="state">(no body cached — refresh the inbox to re-scrape)</p>
            }
          </div>

          @if (e.needsReply && e.replyDrafts) {
            <footer class="foot">
              <span class="foot-label">📩 Inject draft into Gmail:</span>
              @for (t of tones; track t) {
                <button type="button"
                        [disabled]="injecting() !== null"
                        [class.ok]="draftFlash()[t]?.ok === true"
                        [class.err]="draftFlash()[t]?.ok === false"
                        (click)="onInject(t)">
                  @if (draftFlash()[t]; as f) {
                    {{ f.text }}
                  } @else if (injecting() === t) {
                    ⌛ Injecting…
                  } @else {
                    {{ toneLabel[t] }}
                  }
                </button>
              }
            </footer>
          }
        } @else if (errorMsg()) {
          <div class="state err">{{ errorMsg() }}</div>
        } @else {
          <div class="state">Loading…</div>
        }
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
      width: min(900px, 92vw);
      max-height: 90vh;
      display: flex; flex-direction: column;
      color: var(--text-primary);
      box-shadow: 0 20px 60px rgba(0, 0, 0, 0.4);
      overflow: hidden;
    }

    .head {
      display: flex; align-items: flex-start; justify-content: space-between;
      gap: 12px;
      padding: 16px 22px;
      border-bottom: 1px solid var(--border-subtle);
    }
    .meta { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .sender { color: var(--text-heading); font-weight: 600; font-size: 0.95rem; }
    .subject { color: var(--text-primary); font-size: 0.92rem; }
    .time { font-size: 0.78rem; color: var(--text-muted); }
    .close-btn {
      all: unset; cursor: pointer; padding: 4px 8px;
      color: var(--text-muted); font-size: 1rem;
      border-radius: 6px;
    }
    .close-btn:hover { color: var(--text-primary); background: var(--bg-card-hover); }

    .state {
      padding: 30px 22px;
      text-align: center;
      color: var(--text-muted);
    }
    .state.err { color: var(--color-error); }

    .enrichment {
      padding: 14px 22px;
      border-bottom: 1px solid var(--border-subtle);
    }
    .summary {
      margin: 0 0 10px;
      color: var(--text-secondary);
      line-height: 1.5;
    }
    .summary.failed { color: var(--color-error); }
    .badges { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
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

    .ctas { list-style: none; margin: 0; padding: 0; display: grid; gap: 6px; }
    .cta {
      display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px;
      font-size: 0.88rem;
      color: var(--text-secondary);
    }
    .cta::before { content: '▸'; color: var(--accent); }
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
    .cta[data-priority="high"] .cta-type {
      background: rgba(255,110,110,0.10); color: var(--color-error);
    }
    .cta-due { font-size: 0.78rem; color: var(--text-muted); margin-left: auto; }
    .quote {
      flex-basis: 100%;
      margin: 4px 0 0 16px;
      color: var(--text-muted);
      font-style: italic;
      font-size: 0.82rem;
    }

    .body-wrap {
      padding: 14px 22px;
      flex: 1;
      overflow: hidden;
      display: flex; flex-direction: column;
    }
    .body-wrap h4 {
      margin: 0 0 8px;
      font-size: 0.78rem;
      font-weight: 600;
      color: var(--text-muted);
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }
    .body {
      flex: 1;
      overflow-y: auto;
      margin: 0;
      padding: 12px 14px;
      background: var(--bg-input);
      border: 1px solid var(--border-subtle);
      border-radius: 8px;
      font-family: var(--font-sans);
      font-size: 0.88rem;
      color: var(--text-primary);
      line-height: 1.55;
      white-space: pre-wrap;
    }

    .foot {
      display: flex; flex-wrap: wrap; gap: 8px; align-items: center;
      padding: 12px 22px;
      border-top: 1px solid var(--border-subtle);
    }
    .foot-label {
      font-size: 0.82rem;
      color: var(--text-muted);
      margin-right: 4px;
    }
    .foot button {
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
    .foot button:hover:not(:disabled) {
      background: var(--gradient-brand);
      color: #ffffff;
      border-color: var(--accent-2);
    }
    .foot button:disabled { opacity: 0.5; cursor: not-allowed; }
    .foot button.ok {
      background: rgba(102,187,106,0.10);
      color: var(--color-success);
      border-color: var(--color-success);
      opacity: 1;
    }
    .foot button.err {
      background: rgba(255,110,110,0.10);
      color: var(--color-error);
      border-color: var(--color-error);
      opacity: 1;
    }
  `],
})
export class EmailDetailModalComponent implements OnChanges {
  @Input({ required: true }) item!: InboxItem;
  @Output() closed = new EventEmitter<void>();

  private inboxService = inject(InboxService);

  readonly tones: DraftTone[] = ['formal', 'friendly', 'brief'];
  readonly toneLabel: Record<DraftTone, string> = {
    formal: '📩 Formal',
    friendly: '😊 Friendly',
    brief: '⚡ Brief',
  };

  readonly loading = signal<boolean>(true);
  readonly errorMsg = signal<string | null>(null);
  readonly enriched = signal<EnrichedEmail | null>(null);
  readonly body = signal<string>('');
  readonly injecting = signal<DraftTone | null>(null);
  readonly draftFlash = signal<Partial<Record<DraftTone, DraftFlash>>>({});

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['item']) this.load();
  }

  close(): void { this.closed.emit(); }

  /** Esc closes the modal. Listening at document level so the user doesn't
   *  need to focus inside the card first. */
  @HostListener('document:keydown.escape')
  onEscape(): void { this.close(); }

  private load(): void {
    this.loading.set(true);
    this.errorMsg.set(null);
    this.inboxService.details(this.item.id).subscribe({
      next: (res) => {
        this.loading.set(false);
        if (!res.ok) {
          this.errorMsg.set(res.reason || 'Could not load email details');
          return;
        }
        this.enriched.set(res.enriched ?? null);
        this.body.set(res.body ?? '');
      },
      error: (e) => {
        this.loading.set(false);
        this.errorMsg.set(e?.message ?? String(e));
      },
    });
  }

  onInject(tone: DraftTone): void {
    this.injecting.set(tone);
    this.inboxService.injectDraft(this.item.id, tone).subscribe({
      next: (res) => {
        this.injecting.set(null);
        this.flash(tone, res.ok, res.ok
          ? '✓ Pasted in Gmail'
          : `✗ ${res.reason || 'Failed'}`);
      },
      error: (e) => {
        this.injecting.set(null);
        this.flash(tone, false, `✗ ${e.message ?? 'Failed'}`);
      },
    });
  }

  private flash(tone: DraftTone, ok: boolean, text: string): void {
    this.draftFlash.set({ ...this.draftFlash(), [tone]: { ok, text } });
    setTimeout(() => {
      const next = { ...this.draftFlash() };
      delete next[tone];
      this.draftFlash.set(next);
    }, 2000);
  }
}
