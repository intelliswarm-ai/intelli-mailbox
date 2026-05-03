import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { EnrichedEmail, InboxItem, Range } from './inbox.types';

export interface EmailDetails {
  ok: boolean;
  id: string;
  enriched?: EnrichedEmail;
  body?: string;
  reason?: string;
}

export interface InjectDraftResponse {
  ok: boolean;
  id: string;
  tone: string;
  reason?: string;
}

export type DraftTone = 'formal' | 'friendly' | 'brief';

/**
 * REST surface for /api/inbox (the listing) and /api/inbox/process
 * (the explicit "queue these for enrichment" trigger). Streaming
 * enrichment events come through SseService — this is metadata + commands.
 */
@Injectable({ providedIn: 'root' })
export class InboxService {
  private http = inject(HttpClient);

  /** Scrape Gmail's listing + return InboxItem[]. No enrichment is started here. */
  list(range: Range): Observable<InboxItem[]> {
    return this.http.get<InboxItem[]>('/api/inbox', {
      params: new HttpParams().set('range', range),
    });
  }

  /** Explicit "process all visible rows" trigger. Returns counts. */
  processAll(range: Range): Observable<{ ok: boolean; queued: number; total: number; range: string }> {
    return this.http.post<{ ok: boolean; queued: number; total: number; range: string }>(
      `/api/inbox/process?range=${encodeURIComponent(range)}`,
      null,
    );
  }

  /** Force a single row to be re-enriched. Use sparingly — costs LLM tokens. */
  reprocess(id: string): Observable<unknown> {
    return this.http.post(`/api/email/${encodeURIComponent(id)}/reprocess`, null);
  }

  /** Cached enrichment + raw body for the email-detail modal. */
  details(id: string): Observable<EmailDetails> {
    return this.http.get<EmailDetails>(`/api/email/${encodeURIComponent(id)}/details`);
  }

  /** Pastes a pre-drafted reply into Gmail's compose box. The user reviews
   *  and sends from Chrome — we never auto-send. */
  injectDraft(id: string, tone: DraftTone): Observable<InjectDraftResponse> {
    return this.http.post<InjectDraftResponse>(
      `/api/email/${encodeURIComponent(id)}/inject-draft`,
      { tone },
    );
  }
}
