import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface VersionInfo {
  current: string;
  latest: string;
  updateAvailable: boolean;
  releaseUrl: string | null;
  /** Direct download URL for the OS-matching installer (.msi/.dmg/.deb), or null
   *  when the release has no asset for the host OS — the banner falls back to
   *  a plain "View release notes" link in that case. */
  assetUrl: string | null;
  assetName: string | null;
  assetSize: number;
}

export type UpdateState = 'IDLE' | 'DOWNLOADING' | 'READY' | 'INSTALLING' | 'FAILED';

export interface UpdateStatus {
  state: UpdateState;
  bytesRead: number;
  totalBytes: number;
  targetVersion: string | null;
  error: string | null;
}

@Injectable({ providedIn: 'root' })
export class VersionService {
  private http = inject(HttpClient);

  check(): Observable<VersionInfo> {
    return this.http.get<VersionInfo>('/api/version');
  }

  /** Kick off the background download of the OS-matching installer asset.
   *  Idempotent on the backend — calling again while DOWNLOADING/READY is a
   *  no-op that just returns the current snapshot. */
  startDownload(): Observable<UpdateStatus> {
    return this.http.post<UpdateStatus>('/api/update/download', {});
  }

  /** Poll the state machine. The banner only polls while DOWNLOADING. */
  status(): Observable<UpdateStatus> {
    return this.http.get<UpdateStatus>('/api/update/status');
  }

  /** Launch the OS installer and shut the running app down. After this
   *  succeeds the SSE/HTTP connection will drop within ~1s. */
  startInstall(): Observable<UpdateStatus> {
    return this.http.post<UpdateStatus>('/api/update/install', {});
  }
}
