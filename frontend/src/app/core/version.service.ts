import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface VersionInfo {
  current: string;
  latest: string;
  updateAvailable: boolean;
  releaseUrl: string | null;
}

@Injectable({ providedIn: 'root' })
export class VersionService {
  private http = inject(HttpClient);

  check(): Observable<VersionInfo> {
    return this.http.get<VersionInfo>('/api/version');
  }
}
