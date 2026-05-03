import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

interface ListResponse { senders: string[]; total: number; }
interface AddResponse  { added: boolean; sender: string; total: number; evicted?: number; }
interface RemoveResponse { removed: boolean; sender: string; total: number; }

/**
 * "Do not process" sender list — synced with /api/excluded-senders. The
 * service caches the list as a Set so the process picker can do O(1)
 * "is this sender already excluded?" checks while rendering hundreds
 * of rows.
 *
 * Comparison is case-insensitive on the client to match how the picker
 * groups senders (key = display.trim().toLowerCase()).
 */
@Injectable({ providedIn: 'root' })
export class ExcludedSendersService {
  private http = inject(HttpClient);

  private readonly internal = signal<Set<string>>(new Set());

  /** Read-only set of lowercased sender display strings. */
  readonly all = computed(() => this.internal());

  /** Refresh from the server. Call once on app startup; the picker calls
   *  it again after each add to stay in sync. */
  refresh(): Observable<ListResponse> {
    return this.http.get<ListResponse>('/api/excluded-senders').pipe(
      tap((res) => this.internal.set(new Set((res.senders ?? []).map(s => s.trim().toLowerCase())))),
    );
  }

  /** Add a sender to the permanent exclude list (POST), updating local state. */
  add(sender: string): Observable<AddResponse> {
    return this.http.post<AddResponse>('/api/excluded-senders', { sender }).pipe(
      tap((res) => {
        if (res.added) {
          const next = new Set(this.internal());
          next.add(sender.trim().toLowerCase());
          this.internal.set(next);
        }
      }),
    );
  }

  /** Remove a sender from the exclude list (DELETE), updating local state. */
  remove(sender: string): Observable<RemoveResponse> {
    return this.http.delete<RemoveResponse>(
      `/api/excluded-senders/${encodeURIComponent(sender)}`,
    ).pipe(
      tap((res) => {
        if (res.removed) {
          const next = new Set(this.internal());
          next.delete(sender.trim().toLowerCase());
          this.internal.set(next);
        }
      }),
    );
  }

  /** Convenience — case-insensitive membership check. */
  contains(sender: string): boolean {
    return this.internal().has(sender.trim().toLowerCase());
  }
}
