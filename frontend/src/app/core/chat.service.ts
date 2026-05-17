import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface ChatCitation {
  id: string;
  sender: string;
  subject: string;
}

export interface ChatResponse {
  ok: boolean;
  answer: string;
  citations: ChatCitation[];
  error?: string;
}

/**
 * POSTs to /api/chat — backend runs RAG retrieval over the indexed inbox
 * plus tool calling against the pipeline cache. Single round-trip per
 * message; no streaming in Phase 1.
 */
@Injectable({ providedIn: 'root' })
export class ChatService {
  private http = inject(HttpClient);

  ask(message: string): Observable<ChatResponse> {
    return this.http.post<ChatResponse>('/api/chat', { message });
  }
}
