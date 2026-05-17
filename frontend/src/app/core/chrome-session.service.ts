import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface ChromeLogoutResponse {
  ok: boolean;
  message: string;
  filesDeleted: number;
  filesFailed: number;
}

/**
 * Wraps /api/chrome/logout — closes the attached Chrome and wipes every
 * cookie / password / cache file from the dedicated profile directory.
 * Distinct from ShutdownService: this is for security ("hand the machine
 * to someone else"), not "stop the app".
 */
@Injectable({ providedIn: 'root' })
export class ChromeSessionService {
  private http = inject(HttpClient);

  logout(): Observable<ChromeLogoutResponse> {
    return this.http.post<ChromeLogoutResponse>('/api/chrome/logout', null);
  }
}
