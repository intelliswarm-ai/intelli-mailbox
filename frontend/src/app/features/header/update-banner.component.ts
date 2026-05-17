import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { VersionService, VersionInfo } from '../../core/version.service';

/**
 * Single-line banner under the header. Hidden while the version check is
 * pending or when no update is available. Dismiss persists in
 * localStorage keyed by the latest tag, so dismissing once doesn't bury
 * the next release.
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
          Intelli-mailbox <strong>{{ info()!.latest }}</strong> is available
          (you have {{ info()!.current }}).
        </span>
        <a class="link"
           [href]="info()!.releaseUrl || '#'"
           target="_blank"
           rel="noopener">View release notes</a>
        <button type="button"
                class="dismiss"
                (click)="dismiss()"
                title="Hide this banner">✕</button>
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
    .link {
      color: var(--accent);
      text-decoration: none;
      border-bottom: 1px dashed transparent;
      transition: border-color 0.15s;
    }
    .link:hover { border-color: var(--accent); }
    .dismiss {
      all: unset; cursor: pointer;
      color: var(--text-muted);
      padding: 2px 6px; border-radius: 4px;
    }
    .dismiss:hover { color: var(--text-primary); background: rgba(0,0,0,0.06); }
  `],
})
export class UpdateBannerComponent implements OnInit {
  private versionService = inject(VersionService);

  readonly info = signal<VersionInfo | null>(null);
  readonly visible = signal<boolean>(false);

  ngOnInit(): void {
    this.versionService.check().subscribe({
      next: (v) => {
        this.info.set(v);
        if (!v.updateAvailable) return;
        const dismissed = localStorage.getItem('im.update.dismissed');
        if (dismissed === v.latest) return;
        this.visible.set(true);
      },
      error: () => { /* offline / endpoint missing — banner stays hidden */ },
    });
  }

  dismiss(): void {
    const v = this.info();
    if (v) localStorage.setItem('im.update.dismissed', v.latest);
    this.visible.set(false);
  }
}
