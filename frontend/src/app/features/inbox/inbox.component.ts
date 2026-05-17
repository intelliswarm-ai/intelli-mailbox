import {
  Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild,
  computed, inject, signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription, forkJoin, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { InboxService } from '../../core/inbox.service';
import { SseService } from '../../core/sse.service';
import { ExcludedSendersService } from '../../core/excluded-senders.service';
import { EmailOpenerService } from '../../core/email-opener.service';
import {
  ALLOWED_RANGES, EnrichedEmail, InboxItem, RANGE_LABELS, Range, RowStatus,
} from '../../core/inbox.types';
import { InboxRowComponent } from './inbox-row.component';
import { ProcessPickerComponent } from './process-picker.component';
import { StatusFilterBarComponent, StatusFilter } from './status-filter-bar.component';
import { EmailDetailModalComponent } from './email-detail-modal.component';
import { ActionItemsPanelComponent } from './action-items-panel.component';
import { DigestViewComponent } from '../digest/digest-view.component';
import { SuggestionsViewComponent } from '../suggestions/suggestions-view.component';

export type View = 'inbox' | 'digest' | 'suggestions';

const RANGE_KEY = 'intellimailbox-range';
const VIEW_KEY = 'intellimailbox-view';

/**
 * Inbox view — list of email rows that update live via SSE.
 *
 * State (all signals; no innerHTML rewrites):
 *   items()       InboxItem[] from /api/inbox
 *   enriched()    Record<id, EnrichedEmail> — final per-row enrichment
 *   streaming()   Record<id, string> — partial summary tokens before terminal
 *   queued()      Set<string> — rows the user just kicked off via Process all
 *                 / per-row reprocess; cleared by SSE 'email' or 'failed'
 *
 * "Process all" opens a sender picker (legacy ProcessPickerComponent) that
 * lets the user opt out per-batch and 🚫 exclude permanently. On confirm
 * we fire /api/email/{id}/reprocess for every selected id in parallel —
 * the backend's single enricher thread serializes the queue.
 *
 * Status filter pills above the list let the user drill into one bucket.
 *
 * Per-row Analyze button + email-detail modal still deferred — they need
 * the email-body endpoint and are bigger surfaces than this step.
 */
@Component({
  selector: 'im-inbox',
  standalone: true,
  imports: [
    FormsModule, InboxRowComponent, ProcessPickerComponent, StatusFilterBarComponent,
    EmailDetailModalComponent, ActionItemsPanelComponent,
    DigestViewComponent, SuggestionsViewComponent,
  ],
  template: `
    <div class="toolbar">
      <button (click)="refresh()" [disabled]="loading()">
        ↻ Refresh inbox
      </button>
      <button (click)="openProcessPicker()" [disabled]="loading() || items().length === 0"
              title="Run AI analysis on every listed email (skips senders on the excluded list).">
        ▶ Process all
      </button>
      <label class="range-picker" title="How far back to fetch from Gmail">
        <span>📅</span>
        <select [(ngModel)]="rangeModel" (change)="onRangeChange()">
          @for (r of ranges; track r) {
            <option [value]="r">{{ rangeLabels[r] }}</option>
          }
        </select>
      </label>
      <div class="view-toggle" role="group" aria-label="View">
        <button type="button"
                [attr.aria-pressed]="view() === 'inbox'"
                (click)="setView('inbox')"
                title="Flat inbox view (i)">≡ Inbox</button>
        <button type="button"
                [attr.aria-pressed]="view() === 'digest'"
                (click)="setView('digest')"
                title="Daily inbox digest, grouped by badge (d)">▦ Digest</button>
        <button type="button"
                [attr.aria-pressed]="view() === 'suggestions'"
                (click)="setView('suggestions')"
                title="What the AI suggests you act on now (s)">💡 Suggestions</button>
      </div>
      <label class="search-box" [class.active]="searchTerm().length > 0">
        <span>⌕</span>
        <input #searchInput
               type="search"
               placeholder="Search sender / subject / summary…"
               autocomplete="off"
               [value]="searchTerm()"
               (input)="onSearchInput($any($event.target).value)">
        <span class="kbd" title="/ to focus">/</span>
      </label>
      <span class="meta">
        @if (loading()) {
          Refreshing…
        } @else if (items().length) {
          {{ items().length }} item(s), {{ enrichedCount() }} analyzed
          @if (queued().size) { · {{ queued().size }} processing }
        } @else {
          No inbox loaded yet
        }
      </span>
    </div>

    @if (view() === 'inbox' && items().length > 0) {
      <im-status-filter-bar
        [active]="statusFilter()"
        [counts]="statusCounts()"
        (filterChange)="statusFilter.set($event)"
      ></im-status-filter-bar>
    }

    @if (errorMsg()) {
      <div class="error">{{ errorMsg() }}</div>
    }

    @if (view() !== 'suggestions' && items().length > 0) {
      <im-action-items-panel
        [items]="items()"
        [enrichedMap]="enriched()"
        (open)="openDetail($event)"
      ></im-action-items-panel>
    }

    @switch (view()) {
      @case ('inbox') {
        @if (items().length === 0 && !loading()) {
          <div class="empty">
            <p>No emails loaded. Click <b>↻ Refresh inbox</b> to fetch the latest from Gmail.</p>
          </div>
        } @else if (filteredItems().length === 0) {
          <div class="empty">
            <p>
              No emails match the <b>{{ statusFilter() }}</b> filter.
              <button class="link" (click)="statusFilter.set('all')">Clear filter</button>
            </p>
          </div>
        } @else {
          <ul class="rows">
            @for (item of filteredItems(); track item.id) {
              <li>
                <im-inbox-row
                  [item]="item"
                  [enriched]="enrichedFor(item.id)"
                  [streamingSummary]="streamingFor(item.id)"
                  [status]="statusFor(item)"
                  (analyze)="onAnalyzeRow($event)"
                  (open)="openDetail(item)"
                ></im-inbox-row>
              </li>
            }
          </ul>
        }
      }
      @case ('digest') {
        <im-digest-view [enriched]="enrichedAsArray()"></im-digest-view>
      }
      @case ('suggestions') {
        <im-suggestions-view
          [items]="items()"
          [enrichedMap]="enriched()"
        ></im-suggestions-view>
      }
    }

    @if (pickerOpen()) {
      <im-process-picker
        [items]="items()"
        [enriched]="enriched()"
        (confirmed)="onProcessConfirmed($event)"
        (cancelled)="pickerOpen.set(false)"
      ></im-process-picker>
    }

    @if (detailItem(); as it) {
      <im-email-detail-modal
        [item]="it"
        (closed)="detailItem.set(null)"
      ></im-email-detail-modal>
    }
  `,
  styles: [`
    .toolbar {
      padding: 14px 24px;
      background: var(--bg-secondary);
      border-bottom: 1px solid var(--border-section);
      display: flex; gap: 10px; align-items: center; flex-wrap: wrap;
    }
    .toolbar button {
      background: var(--accent-dim); color: var(--accent);
      border: 1px solid var(--accent);
      padding: 7px 14px; border-radius: 8px;
      font-family: inherit; font-size: 0.85rem; font-weight: 500;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .toolbar button:hover:not(:disabled) {
      background: var(--gradient-brand);
      color: #ffffff;
      border-color: var(--accent-2);
      box-shadow: 0 0 12px var(--accent-glow);
    }
    .toolbar button:disabled { opacity: 0.4; cursor: not-allowed; }

    .range-picker {
      display: inline-flex; align-items: center; gap: 6px;
      padding: 5px 10px; border-radius: 8px;
      background: var(--bg-input);
      border: 1px solid var(--border-subtle);
      cursor: pointer;
    }
    .range-picker:hover { border-color: var(--accent); }
    .range-picker select {
      all: unset; cursor: pointer;
      font-family: var(--font-sans); font-size: 0.85rem; font-weight: 500;
      color: var(--text-primary);
    }

    .view-toggle {
      display: inline-flex; align-items: center;
      background: var(--bg-input);
      border: 1px solid var(--border-subtle);
      border-radius: 999px;
      padding: 3px;
    }
    .view-toggle button {
      all: unset;
      cursor: pointer;
      font-family: inherit;
      font-size: 0.78rem;
      font-weight: 500;
      color: var(--text-muted);
      padding: 5px 12px;
      border-radius: 999px;
      display: inline-flex; align-items: center; gap: 4px;
      transition: color 0.2s, background 0.2s;
    }
    .view-toggle button[aria-pressed="true"] {
      background: var(--gradient-brand);
      color: #ffffff;
      box-shadow: 0 0 12px var(--accent-glow);
    }
    :host-context([data-theme="light"]) .view-toggle button[aria-pressed="true"] {
      background: var(--accent);
      color: #ffffff;
      box-shadow: none;
    }
    .view-toggle button:hover:not([aria-pressed="true"]) { color: var(--text-primary); }

    .search-box {
      display: inline-flex; align-items: center; gap: 6px;
      padding: 4px 10px; border-radius: 8px;
      background: var(--bg-input);
      border: 1px solid var(--border-subtle);
      transition: border-color 0.15s;
    }
    .search-box:hover, .search-box.active, .search-box:focus-within {
      border-color: var(--accent);
    }
    .search-box input {
      all: unset;
      font-family: inherit; font-size: 0.85rem;
      color: var(--text-primary);
      width: 220px;
    }
    .search-box input::placeholder { color: var(--text-muted); }
    .search-box .kbd {
      font-family: var(--font-mono); font-size: 0.7rem;
      color: var(--text-muted);
      padding: 1px 5px;
      border: 1px solid var(--border-subtle);
      border-radius: 4px;
    }

    .meta {
      font-size: 0.8rem; color: var(--text-muted); margin-left: auto;
    }

    .error {
      margin: 14px 24px;
      padding: 10px 14px;
      border-radius: 8px;
      background: rgba(255,110,110,0.10);
      color: var(--color-error);
      font-size: 0.88rem;
    }

    .empty {
      padding: 60px 24px; text-align: center; color: var(--text-muted);
    }
    .empty .link {
      all: unset;
      cursor: pointer;
      color: var(--accent);
      margin-left: 6px;
      text-decoration: underline;
    }

    .rows {
      list-style: none; margin: 20px auto; padding: 0 24px;
      max-width: 1100px;
    }
  `],
})
export class InboxComponent implements OnInit, OnDestroy {
  private inboxService = inject(InboxService);
  private sseService = inject(SseService);
  private excludedService = inject(ExcludedSendersService);
  private emailOpener = inject(EmailOpenerService);

  private subs = new Subscription();

  readonly ranges = ALLOWED_RANGES;
  readonly rangeLabels = RANGE_LABELS;

  rangeModel: Range = '1d';

  readonly items = signal<InboxItem[]>([]);
  readonly enriched = signal<Record<string, EnrichedEmail>>({});
  readonly streaming = signal<Record<string, string>>({});
  readonly queued = signal<Set<string>>(new Set());
  readonly loading = signal<boolean>(false);
  readonly errorMsg = signal<string | null>(null);
  readonly pickerOpen = signal<boolean>(false);
  readonly statusFilter = signal<StatusFilter>('all');
  readonly view = signal<View>('inbox');
  readonly searchTerm = signal<string>('');
  /** When non-null, the email-detail modal is open for this row. */
  readonly detailItem = signal<InboxItem | null>(null);

  @ViewChild('searchInput') searchInputEl?: ElementRef<HTMLInputElement>;

  /** Digest takes a flat array; computed once per render rather than per row. */
  readonly enrichedAsArray = computed<EnrichedEmail[]>(() =>
    this.items()
      .map(it => this.enriched()[it.id])
      .filter((e): e is EnrichedEmail => !!e && !e.failed));

  private searchDebounceHandle: ReturnType<typeof setTimeout> | null = null;

  /** Live counts per status bucket — derived from items + enriched + streaming + queued + excluded. */
  readonly statusCounts = computed<Record<StatusFilter, number>>(() => {
    const counts: Record<StatusFilter, number> = {
      all: this.items().length,
      analyzing: 0, analyzed: 0, pending: 0, excluded: 0, failed: 0,
    };
    for (const it of this.items()) counts[this.statusFor(it)]++;
    return counts;
  });

  /** Items filtered to the active status bucket AND search term. */
  readonly filteredItems = computed<InboxItem[]>(() => {
    const f = this.statusFilter();
    const term = this.searchTerm().toLowerCase();
    let out = this.items();
    if (f !== 'all') out = out.filter(it => this.statusFor(it) === f);
    if (term) {
      out = out.filter(it => this.matchesSearch(it, term));
    }
    return out;
  });

  /** Search across sender, subject, and (when present) the AI summary. */
  private matchesSearch(item: InboxItem, term: string): boolean {
    if ((item.sender || '').toLowerCase().includes(term)) return true;
    if ((item.subject || '').toLowerCase().includes(term)) return true;
    const e = this.enriched()[item.id];
    if (e && (e.summary || '').toLowerCase().includes(term)) return true;
    return false;
  }

  ngOnInit(): void {
    try {
      const saved = localStorage.getItem(RANGE_KEY);
      if (saved && (ALLOWED_RANGES as string[]).includes(saved)) {
        this.rangeModel = saved as Range;
      }
      const savedView = localStorage.getItem(VIEW_KEY);
      if (savedView === 'inbox' || savedView === 'digest' || savedView === 'suggestions') {
        this.view.set(savedView);
      }
    } catch { /* ignore */ }

    // Chat citation chips → open the matching email's detail modal.
    // The chat panel doesn't have items() in scope, so it delegates the
    // resolution to here where the id can be looked up against the
    // current listing.
    this.subs.add(this.emailOpener.open$.subscribe((id) => this.openById(id)));

    // SSE first so we don't miss enrichment events for rows the pipeline is
    // already processing.
    this.sseService.connect();
    this.subs.add(this.sseService.summaryDelta$.subscribe((d) => {
      const map = this.streaming();
      this.streaming.set({ ...map, [d.emailId]: (map[d.emailId] ?? '') + d.text });
    }));
    this.subs.add(this.sseService.enriched$.subscribe((e) => {
      const id = e.item.id;
      const all = this.enriched();
      this.enriched.set({ ...all, [id]: e });
      // Drop streaming buffer for this id.
      const stream = this.streaming();
      if (stream[id] !== undefined) {
        const next = { ...stream };
        delete next[id];
        this.streaming.set(next);
      }
      // Drop from queued — no longer "in-flight".
      const q = this.queued();
      if (q.has(id)) {
        const nextQ = new Set(q);
        nextQ.delete(id);
        this.queued.set(nextQ);
      }
    }));

    // Excluded senders — we need this to compute the 'excluded' row status.
    this.excludedService.refresh().subscribe({ error: () => { /* keep empty set */ } });

    this.refresh();
  }

  ngOnDestroy(): void {
    this.subs.unsubscribe();
  }

  refresh(): void {
    this.loading.set(true);
    this.errorMsg.set(null);
    this.inboxService.list(this.rangeModel).subscribe({
      next: (rows) => {
        this.items.set(rows);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.errorMsg.set(err?.message ?? String(err));
      },
    });
  }

  openProcessPicker(): void {
    if (this.items().length === 0) return;
    this.pickerOpen.set(true);
  }

  /** Picker emits the ids the user wants processed. We optimistically clear
   *  cached enrichment for those ids (so the row flips to "Analyzing…") then
   *  fire /reprocess for each in parallel. */
  onProcessConfirmed(ids: string[]): void {
    this.pickerOpen.set(false);
    if (ids.length === 0) return;
    // Optimistic: drop cached enrichment + streaming, mark as queued.
    const enrichedNext = { ...this.enriched() };
    const streamingNext = { ...this.streaming() };
    const queuedNext = new Set(this.queued());
    for (const id of ids) {
      delete enrichedNext[id];
      delete streamingNext[id];
      queuedNext.add(id);
    }
    this.enriched.set(enrichedNext);
    this.streaming.set(streamingNext);
    this.queued.set(queuedNext);

    // Fire all reprocess requests in parallel. Backend's single enricher
    // thread serializes server-side anyway.
    forkJoin(ids.map(id =>
      this.inboxService.reprocess(id).pipe(
        map(() => ({ id, ok: true })),
        catchError(() => of({ id, ok: false })),
      ),
    )).subscribe((results) => {
      const failed = results.filter(r => !r.ok).map(r => r.id);
      if (failed.length) {
        // For failures, drop them from queued — they'll never land via SSE.
        const q = new Set(this.queued());
        for (const id of failed) q.delete(id);
        this.queued.set(q);
        this.errorMsg.set(`${failed.length} email(s) failed to enqueue.`);
      }
    });
  }

  onRangeChange(): void {
    try { localStorage.setItem(RANGE_KEY, this.rangeModel); } catch { /* ignore */ }
    this.refresh();
  }

  setView(v: View): void {
    this.view.set(v);
    try { localStorage.setItem(VIEW_KEY, v); } catch { /* ignore */ }
  }

  /** Debounced — 150 ms — so each keystroke doesn't recompute the visible list. */
  onSearchInput(value: string): void {
    if (this.searchDebounceHandle !== null) clearTimeout(this.searchDebounceHandle);
    this.searchDebounceHandle = setTimeout(() => {
      this.searchTerm.set(value.trim());
      this.searchDebounceHandle = null;
    }, 150);
  }

  openDetail(item: InboxItem): void {
    this.detailItem.set(item);
  }

  /** Open the detail modal for the email with the given id, looked up
   *  against the current listing. Used by chat citation clicks. If the
   *  id isn't in the visible listing (different range, scrolled off,
   *  etc.) we log silently rather than crash — the user can refresh
   *  or change range to see it. */
  private openById(id: string): void {
    const match = this.items().find((it) => it.id === id);
    if (match) {
      this.detailItem.set(match);
    }
    // Else: the chat agent cited an id from the cache that isn't in the
    // current listing view. Could fetch via /api/email/{id}/details and
    // build a synthetic InboxItem, but for v1 we silently no-op so
    // citation clicks never throw.
  }

  /** Per-row 🔍 Analyze — same flow as Process all, scoped to one id. */
  onAnalyzeRow(id: string): void {
    // Optimistic: mark queued so the row flips to ⌛ Analyzing.
    const enrichedNext = { ...this.enriched() };
    delete enrichedNext[id];
    this.enriched.set(enrichedNext);
    const queuedNext = new Set(this.queued());
    queuedNext.add(id);
    this.queued.set(queuedNext);

    this.inboxService.reprocess(id).subscribe({
      error: () => {
        // Roll back queued so the row reverts to its previous status.
        const q = new Set(this.queued());
        q.delete(id);
        this.queued.set(q);
        this.errorMsg.set(`Failed to enqueue email ${id}.`);
      },
    });
  }

  /**
   * Inbox-scoped hotkeys. AppComponent already handles `?` (help) and `Esc`
   * (close modals); this listener picks up the rest from the legacy:
   *   r        — refresh inbox
   *   i / d / s — switch view
   *   /        — focus the search box
   *
   * Skipped when the user is typing in any input/textarea/contenteditable —
   * including our own search box (so typing 's' in search isn't hijacked).
   */
  @HostListener('document:keydown', ['$event'])
  onHotkey(e: KeyboardEvent): void {
    if (e.altKey || e.ctrlKey || e.metaKey) return;
    if (this.isTyping(e.target)) {
      // Esc inside the search box clears it.
      if (e.key === 'Escape' && e.target === this.searchInputEl?.nativeElement) {
        this.searchTerm.set('');
        if (this.searchInputEl) this.searchInputEl.nativeElement.value = '';
      }
      return;
    }
    switch (e.key) {
      case 'r': e.preventDefault(); this.refresh(); break;
      case 'i': e.preventDefault(); this.setView('inbox'); break;
      case 'd': e.preventDefault(); this.setView('digest'); break;
      case 's': e.preventDefault(); this.setView('suggestions'); break;
      case '/': e.preventDefault(); this.searchInputEl?.nativeElement.focus(); break;
    }
  }

  private isTyping(target: EventTarget | null): boolean {
    if (!(target instanceof HTMLElement)) return false;
    const tag = target.tagName;
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
  }

  enrichedFor(id: string): EnrichedEmail | null {
    return this.enriched()[id] ?? null;
  }

  streamingFor(id: string): string | null {
    return this.streaming()[id] ?? null;
  }

  /**
   * Row-status precedence (matches legacy getRowStatus exactly):
   *   1. excluded   — sender is on the permanent excluded list
   *   2. failed     — terminal enrichment with failed=true
   *   3. analyzed   — terminal enrichment with failed=false
   *   4. analyzing  — queued for re-processing OR streaming summary in flight
   *   5. pending    — none of the above
   */
  statusFor(item: InboxItem): RowStatus {
    const senderKey = (item.sender || '').trim().toLowerCase();
    if (this.excludedService.all().has(senderKey)) return 'excluded';
    const e = this.enriched()[item.id];
    if (e?.failed) return 'failed';
    if (e) return 'analyzed';
    if (this.queued().has(item.id) || this.streaming()[item.id]) return 'analyzing';
    return 'pending';
  }

  enrichedCount(): number {
    return Object.keys(this.enriched()).length;
  }
}
