import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, of } from 'rxjs';

/**
 * Wraps POST /api/shutdown. Server side has the watchdog + ContextClosedEvent
 * teardown (closes SSE, kills tabs, halts JVM at the deadline) — this service
 * just fires the request and lets the UI render the "stopped" state.
 *
 * fire() never throws — if the connection drops mid-response (because the
 * server cuts itself off, which is fine), we still resolve so the UI flow
 * completes.
 */
@Injectable({ providedIn: 'root' })
export class ShutdownService {
  private http = inject(HttpClient);

  fire(): Observable<unknown> {
    return this.http.post('/api/shutdown', null).pipe(
      catchError(() => of(null)),
    );
  }
}
