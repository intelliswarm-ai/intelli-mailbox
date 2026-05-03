import { HttpClient } from '@angular/common/http';
import { Inject, Injectable, NgZone, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { Observable } from 'rxjs';
import { DiagnosticsResponse, FixProgress, FixResponse } from './diagnostics.types';

/**
 * Welcome-screen diagnostics:
 *   GET  /api/diagnostics            — run all checks
 *   POST /api/diagnostics/fix/{a}    — one-shot auto-fix
 *   GET  /api/diagnostics/fix/{a}    — SSE progress stream (pull-model, install-ollama)
 *
 * The SSE stream uses a separate transient EventSource per fix; it's deliberately
 * not the shared SseService (which is /api/inbox/stream).
 */
@Injectable({ providedIn: 'root' })
export class DiagnosticsService {
  private http = inject(HttpClient);

  constructor(
    @Inject(PLATFORM_ID) private platformId: object,
    private zone: NgZone,
  ) {}

  run(): Observable<DiagnosticsResponse> {
    return this.http.get<DiagnosticsResponse>('/api/diagnostics');
  }

  /** Non-streaming fix — used for fast actions (open-gmail, open-ollama-download). */
  fix(action: string): Observable<FixResponse> {
    return this.http.post<FixResponse>(
      `/api/diagnostics/fix/${encodeURIComponent(action)}`, null,
    );
  }

  /**
   * SSE progress stream for long-running fixes (pull-model, install-ollama).
   * Returns an Observable that emits FixProgress events. The phase=done /
   * phase=error events terminate the stream — caller should keep the
   * subscription open until then; the service closes the EventSource for it.
   */
  streamFix(action: string): Observable<FixProgress> {
    return new Observable<FixProgress>((subscriber) => {
      if (!isPlatformBrowser(this.platformId)) {
        subscriber.complete();
        return;
      }
      const src = new EventSource(`/api/diagnostics/fix/${encodeURIComponent(action)}`);
      const close = () => { try { src.close(); } catch { /* */ } };

      src.addEventListener('progress', (ev) => {
        try {
          const data = JSON.parse((ev as MessageEvent).data) as FixProgress;
          this.zone.run(() => {
            subscriber.next(data);
            if (data.phase === 'done' || data.phase === 'error') {
              close();
              subscriber.complete();
            }
          });
        } catch { /* malformed payload — ignore */ }
      });

      src.onerror = () => {
        this.zone.run(() => {
          subscriber.error(new Error('SSE transport error'));
        });
        close();
      };

      // Teardown: caller unsubscribed before completion.
      return () => close();
    });
  }
}
