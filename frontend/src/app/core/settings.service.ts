import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { SaveDraft, SaveResponse, SettingsResponse } from './settings.types';

/**
 * Wraps GET/POST /api/settings. The server is the source of truth — the
 * service keeps the latest snapshot in a signal so the header pill and
 * the modal stay in sync without prop drilling.
 */
@Injectable({ providedIn: 'root' })
export class SettingsService {
  private http = inject(HttpClient);

  /** Latest server snapshot, or null until the first GET completes. */
  private readonly snapshot = signal<SettingsResponse | null>(null);

  /** Read-only access for templates. */
  readonly current = this.snapshot.asReadonly();

  /** Convenience derived signal — the active provider's display info. */
  readonly active = computed(() => this.snapshot()?.active ?? null);

  /** Fetch fresh state. Returns the Observable so callers can chain. */
  load(): Observable<SettingsResponse> {
    return this.http.get<SettingsResponse>('/api/settings').pipe(
      tap((res) => this.snapshot.set(res)),
    );
  }

  /** Persist a draft. The server merges + replies with the new active summary. */
  save(draft: SaveDraft): Observable<SaveResponse> {
    return this.http.post<SaveResponse>('/api/settings', draft).pipe(
      tap((res) => {
        // Refresh the cached active summary so the header pill updates immediately.
        if (res.ok && res.active) {
          const prev = this.snapshot();
          if (prev) {
            this.snapshot.set({ ...prev, active: res.active, activeProvider: res.active.id });
          }
        }
      }),
    );
  }
}
