import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { UpdateState, UpdateStatus, VersionInfo, VersionService } from '../../core/version.service';

/**
 * One-line banner under the header that drives the in-app upgrade flow for
 * issue #3. Replaces the previous "go to GitHub and find the asset yourself"
 * design with: download → install → restart, all from one banner.
 *
 * State machine (mirrors {@link UpdateState} on the backend):
 *
 *   Available    ─► [Download update]
 *   Downloading  ─► progress bar XX %
 *   Ready        ─► [Install update]
 *   Installing   ─► spinner — app is quitting
 *   Failed       ─► error + retry link
 *
 * Dismiss persists in localStorage keyed by the latest tag so dismissing
 * once doesn't bury the next release.
 */
@Component({
  selector: 'im-update-banner',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule],
  template: `
    @if (visible()) {
      <div class="banner" role="status">
        <span class="icon">⬆</span>
        <span class="text">
          @switch (status().state) {
            @case ('DOWNLOADING') {
              Downloading Intelli-mailbox <strong>{{ info()!.latest }}</strong> —
              <span class="pct">{{ percentLabel() }}</span>
            }
            @case ('READY') {
              Intelli-mailbox <strong>{{ info()!.latest }}</strong> is ready to install.
              The app will quit and relaunch automatically.
            }
            @case ('INSTALLING') {
              Installing Intelli-mailbox <strong>{{ info()!.latest }}</strong> —
              the installer is taking over.
            }
            @case ('FAILED') {
              Update failed: {{ status().error || 'unknown error' }}
            }
            @default {
              Intelli-mailbox <strong>{{ info()!.latest }}</strong> is available
              (you have {{ info()!.current }}).
            }
          }
        </span>

        @if (status().state === 'DOWNLOADING') {
          <div class="progress" [attr.aria-valuenow]="percent()" aria-valuemin="0" aria-valuemax="100" role="progressbar">
            <div class="bar" [style.width.%]="percent()"></div>
          </div>
        }

        @if (status().state === 'IDLE' || status().state === 'FAILED') {
          @if (info()?.assetUrl) {
            <button type="button" class="action" (click)="download()">
              {{ status().state === 'FAILED' ? 'Retry' : 'Download & install' }}
            </button>
          }
          <a class="link"
             [href]="info()!.releaseUrl || '#'"
             target="_blank"
             rel="noopener">View release notes</a>
        }
        @if (status().state === 'READY') {
          <button type="button" class="action primary" (click)="install()">
            Install update
          </button>
        }

        @if (status().state === 'IDLE' || status().state === 'FAILED') {
          <button type="button"
                  class="dismiss"
                  (click)="dismiss()"
                  title="Hide this banner">✕</button>
        }
      </div>
    }
  `,
  styles: [`
    .banner {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 8px 18px;
      background: linear-gradient(90deg, rgba(245, 185, 66, 0.18),
                                          rgba(245, 185, 66, 0.06));
      border-bottom: 1px solid rgba(245, 185, 66, 0.45);
      color: var(--text-primary);
      font-size: 0.85rem;
    }
    .icon { font-size: 1rem; }
    .text { flex: 1; }
    .text strong { font-weight: 600; }
    .pct { font-variant-numeric: tabular-nums; font-weight: 600; }
    .progress {
      width: 140px; height: 6px;
      background: rgba(255, 255, 255, 0.10);
      border-radius: 3px; overflow: hidden;
    }
    .progress .bar {
      height: 100%;
      background: var(--gradient-brand);
      transition: width 0.2s ease;
    }
    .link {
      color: var(--accent);
      text-decoration: none;
      border-bottom: 1px dashed transparent;
      transition: border-color 0.15s;
    }
    .link:hover { border-color: var(--accent); }
    .action {
      all: unset; cursor: pointer;
      padding: 4px 12px; border-radius: 6px;
      background: rgba(255, 255, 255, 0.08);
      color: var(--text-primary);
      font-weight: 600; font-size: 0.82rem;
      border: 1px solid rgba(255, 255, 255, 0.12);
    }
    .action:hover { background: rgba(255, 255, 255, 0.14); }
    .action.primary {
      background: var(--gradient-brand);
      color: #ffffff;
      border-color: transparent;
    }
    .action.primary:hover { filter: brightness(1.05); }
    .dismiss {
      all: unset; cursor: pointer;
      color: var(--text-muted);
      padding: 2px 6px; border-radius: 4px;
    }
    .dismiss:hover { color: var(--text-primary); background: rgba(0,0,0,0.06); }
  `],
})
export class UpdateBannerComponent implements OnInit, OnDestroy {
  private versionService = inject(VersionService);

  readonly info = signal<VersionInfo | null>(null);
  readonly status = signal<UpdateStatus>({
    state: 'IDLE', bytesRead: 0, totalBytes: 0, targetVersion: null, error: null,
  });
  readonly visible = signal<boolean>(false);

  readonly percent = computed(() => {
    const s = this.status();
    if (s.totalBytes <= 0) return 0;
    return Math.min(100, Math.floor((s.bytesRead / s.totalBytes) * 100));
  });
  readonly percentLabel = computed(() => `${this.percent()} %`);

  /** Backend defers its first GitHub poll a few seconds after boot. The UI's
   *  initial /api/version request can land before that poll completes,
   *  returning a placeholder "no update available". We re-check after a
   *  short delay to catch that case, then every 30 min while the app is
   *  open so a release published mid-session also surfaces. */
  private static readonly INITIAL_RECHECK_MS = 10_000;
  private static readonly POLL_INTERVAL_MS = 30 * 60 * 1000;
  /** While the backend is downloading the installer, poll faster so the
   *  progress bar visibly fills. Stops the moment we leave DOWNLOADING. */
  private static readonly DOWNLOAD_POLL_MS = 750;

  private recheckHandle: ReturnType<typeof setTimeout> | null = null;
  private pollHandle: ReturnType<typeof setInterval> | null = null;
  private downloadPollHandle: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    this.fetch();
    // First belt-and-braces refresh — catches the deferred-startup-poll case.
    this.recheckHandle = setTimeout(() => this.fetch(), UpdateBannerComponent.INITIAL_RECHECK_MS);
    // Long-running refresh for mid-session releases.
    this.pollHandle = setInterval(() => this.fetch(), UpdateBannerComponent.POLL_INTERVAL_MS);
  }

  ngOnDestroy(): void {
    if (this.recheckHandle !== null) clearTimeout(this.recheckHandle);
    if (this.pollHandle !== null) clearInterval(this.pollHandle);
    this.stopDownloadPoll();
  }

  dismiss(): void {
    const v = this.info();
    if (v) localStorage.setItem('im.update.dismissed', v.latest);
    this.visible.set(false);
  }

  download(): void {
    this.versionService.startDownload().subscribe({
      next: (s) => {
        this.status.set(s);
        if (s.state === 'DOWNLOADING') this.startDownloadPoll();
      },
      error: () => {
        this.status.set({
          ...this.status(), state: 'FAILED',
          error: 'Could not start the download. Check your internet connection.',
        });
      },
    });
  }

  install(): void {
    this.versionService.startInstall().subscribe({
      next: (s) => this.status.set(s),
      error: () => {
        // After install fires, the backend exits the JVM ~1s later and the
        // next requests will fail. That's expected and not a real error.
        this.status.set({ ...this.status(), state: 'INSTALLING' });
      },
    });
  }

  private startDownloadPoll(): void {
    if (this.downloadPollHandle !== null) return;
    this.downloadPollHandle = setInterval(() => {
      this.versionService.status().subscribe({
        next: (s) => {
          this.status.set(s);
          if (s.state !== 'DOWNLOADING') this.stopDownloadPoll();
        },
        error: () => this.stopDownloadPoll(),
      });
    }, UpdateBannerComponent.DOWNLOAD_POLL_MS);
  }

  private stopDownloadPoll(): void {
    if (this.downloadPollHandle !== null) {
      clearInterval(this.downloadPollHandle);
      this.downloadPollHandle = null;
    }
  }

  private fetch(): void {
    this.versionService.check().subscribe({
      next: (v) => {
        this.info.set(v);
        if (!v.updateAvailable) {
          // Backend may have not yet completed its first poll — leave
          // the banner hidden and let the timer above re-fetch.
          return;
        }
        const dismissed = localStorage.getItem('im.update.dismissed');
        if (dismissed === v.latest) return;
        this.visible.set(true);
      },
      error: () => { /* offline / endpoint missing — banner stays hidden */ },
    });
  }
}
