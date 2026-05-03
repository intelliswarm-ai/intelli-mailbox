import { Inject, Injectable, PLATFORM_ID, signal } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';

export type Theme = 'intelliswarm' | 'light';

const STORAGE_KEY = 'intellimailbox-theme';

/**
 * Theme service. Two palettes: `intelliswarm` (dark, default) and `light`.
 * Toggling sets [data-theme] on <html>; CSS variables in styles.scss flip
 * to the matching palette. Choice persists in localStorage.
 *
 * Browser-only: SSR-safe via isPlatformBrowser guard, though we don't ship
 * SSR yet — keeping this defensive so a future change doesn't crash.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly current = signal<Theme>('intelliswarm');

  /** Read-only signal — components bind to this for aria-pressed states. */
  readonly theme = this.current.asReadonly();

  constructor(@Inject(PLATFORM_ID) private platformId: object) {
    if (isPlatformBrowser(this.platformId)) {
      let saved: string | null = null;
      try { saved = localStorage.getItem(STORAGE_KEY); } catch { /* private mode etc. */ }
      this.apply((saved === 'light' ? 'light' : 'intelliswarm'));
    }
  }

  setTheme(theme: Theme): void {
    this.apply(theme);
  }

  private apply(theme: Theme): void {
    this.current.set(theme);
    if (!isPlatformBrowser(this.platformId)) return;
    if (theme === 'light') {
      document.documentElement.setAttribute('data-theme', 'light');
    } else {
      document.documentElement.removeAttribute('data-theme');
    }
    try { localStorage.setItem(STORAGE_KEY, theme); } catch { /* ignore */ }
  }
}
