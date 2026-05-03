import { Inject, Injectable, NgZone, PLATFORM_ID } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { Observable, Subject } from 'rxjs';
import { EnrichedEvent, SummaryDelta } from './inbox.types';

/**
 * Server-Sent Events client for /api/inbox/stream. Two event types come
 * through the same connection — the service demuxes them into two RxJS
 * Subjects so components can subscribe independently.
 *
 * Browsers (and EventSource specifically) reconnect automatically on
 * transport failure. We only surface that as a reconnects$ event for
 * status-pill indicators.
 *
 * Runs in NgZone so signal updates triggered from event handlers cause
 * change detection.
 */
@Injectable({ providedIn: 'root' })
export class SseService {
  private source: EventSource | null = null;

  private readonly summaryDeltas = new Subject<SummaryDelta>();
  private readonly enrichedEvents = new Subject<EnrichedEvent>();
  private readonly connectionStatus = new Subject<'open' | 'error'>();

  readonly summaryDelta$: Observable<SummaryDelta> = this.summaryDeltas.asObservable();
  readonly enriched$: Observable<EnrichedEvent> = this.enrichedEvents.asObservable();
  readonly status$: Observable<'open' | 'error'> = this.connectionStatus.asObservable();

  constructor(
    @Inject(PLATFORM_ID) private platformId: object,
    private zone: NgZone,
  ) {}

  /** Idempotent — if a connection is already open, returns. */
  connect(): void {
    if (!isPlatformBrowser(this.platformId)) return;
    if (this.source) return;
    const es = new EventSource('/api/inbox/stream');

    es.addEventListener('summary_delta', (ev) => {
      try {
        const data = JSON.parse((ev as MessageEvent).data) as SummaryDelta;
        this.zone.run(() => this.summaryDeltas.next(data));
      } catch { /* malformed payload — ignore */ }
    });

    es.addEventListener('email', (ev) => {
      try {
        const data = JSON.parse((ev as MessageEvent).data) as EnrichedEvent;
        this.zone.run(() => this.enrichedEvents.next(data));
      } catch { /* malformed payload — ignore */ }
    });

    es.onopen = () => this.zone.run(() => this.connectionStatus.next('open'));
    es.onerror = () => this.zone.run(() => this.connectionStatus.next('error'));

    this.source = es;
  }

  disconnect(): void {
    if (!this.source) return;
    this.source.close();
    this.source = null;
  }
}
