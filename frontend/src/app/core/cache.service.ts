import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface ClearCacheResponse {
  ok: boolean;
  enrichmentsCleared: number;
  bodiesCleared: number;
  vectorsCleared: number;
  message: string;
}

/**
 * /api/cache/clear — wipes every AI-derived artifact (enrichments, raw
 * bodies, vector embeddings) so the next ▶ Process recomputes from
 * scratch. Gmail credentials are untouched (that's /api/chrome/logout).
 */
@Injectable({ providedIn: 'root' })
export class CacheService {
  private http = inject(HttpClient);

  clear(): Observable<ClearCacheResponse> {
    return this.http.post<ClearCacheResponse>('/api/cache/clear', null);
  }
}
