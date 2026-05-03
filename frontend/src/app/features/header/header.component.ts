import { Component, EventEmitter, Output, inject } from '@angular/core';
import { ThemeService, Theme } from '../../core/theme.service';
import { SettingsService } from '../../core/settings.service';

/**
 * Top header bar — brand block, theme toggle, status indicator.
 * Ported from legacy/index.html lines 1571–1586.
 *
 * Status dot is hardcoded to "Idle / green" for now; it will become live
 * in step 5 when the SSE service lands and we know the connection state.
 */
@Component({
  selector: 'im-header',
  standalone: true,
  template: `
    <header>
      <div class="brand-block">
        <h1>Intelli<span class="accent">-mailbox</span></h1>
        <a class="brand-poweredby"
           href="https://intelliswarm.ai"
           target="_blank"
           rel="noopener"
           title="Built on the Intelliswarm AI agent framework">
          powered by <span class="brand-mark">intelliswarm<span class="dot-tld">.ai</span></span>
        </a>
      </div>
      <span class="spacer"></span>
      @if (active(); as a) {
        <button type="button"
                class="active-llm-pill"
                (click)="onSettings.emit()"
                [title]="'Active LLM: ' + a.label + ' · ' + a.model + ' (click to change)'">
          <span class="pill-icon">{{ a.icon || '·' }}</span>
          <span class="pill-label">{{ a.isLocal ? 'Local' : 'Cloud' }}</span>
          <span class="pill-sep">·</span>
          <span class="pill-model">{{ a.model }}</span>
        </button>
      }
      <div class="theme-toggle" role="group" aria-label="Theme">
        <button type="button"
                [attr.aria-pressed]="theme() === 'intelliswarm'"
                (click)="setTheme('intelliswarm')"
                title="Intelliswarm theme">✦ Intelliswarm</button>
        <button type="button"
                [attr.aria-pressed]="theme() === 'light'"
                (click)="setTheme('light')"
                title="Light theme">☀ Light</button>
      </div>
      <button type="button"
              class="icon-btn"
              (click)="onSettings.emit()"
              title="LLM settings (Ollama / OpenAI / Anthropic / …)">⚙ Settings</button>
      <button type="button"
              class="icon-btn"
              (click)="onHelp.emit()"
              title="Keyboard shortcuts (?)">?</button>
      <button type="button"
              class="icon-btn danger"
              (click)="onQuit.emit()"
              title="Stop the Intelli-mailbox app entirely (closes the local server, frees ~310 MB RAM)">⏻ Quit</button>
      <div class="status">
        <span class="dot"></span>
        <span>Idle</span>
      </div>
    </header>
  `,
  styles: [`
    header {
      background: var(--bg-header);
      border-bottom: 1px solid var(--border-section);
      backdrop-filter: var(--header-blur);
      padding: 14px 24px;
      display: flex;
      align-items: center;
      gap: 16px;
      position: sticky;
      top: 0;
      z-index: 50;
    }

    .brand-block {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    h1 {
      margin: 0;
      font-size: 1.2rem;
      font-weight: 600;
      letter-spacing: 0.02em;
      color: var(--text-heading);
    }
    h1 .accent {
      background: var(--gradient-brand);
      -webkit-background-clip: text;
      background-clip: text;
      color: transparent;
    }
    .brand-poweredby {
      font-size: 0.7rem;
      color: var(--text-muted);
      text-decoration: none;
      letter-spacing: 0.03em;
    }
    .brand-poweredby:hover { color: var(--accent); }
    .brand-mark {
      color: var(--text-secondary);
      font-weight: 500;
    }
    .brand-mark .dot-tld { color: var(--accent); }

    .spacer { flex: 1; }

    .theme-toggle {
      display: inline-flex;
      align-items: center;
      background: var(--bg-input);
      border: 1px solid var(--border-subtle);
      border-radius: 999px;
      padding: 3px;
      gap: 0;
    }
    .theme-toggle button {
      all: unset;
      cursor: pointer;
      font-family: inherit;
      font-size: 0.78rem;
      font-weight: 500;
      color: var(--text-muted);
      padding: 5px 12px;
      border-radius: 999px;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      transition: color 0.2s, background 0.2s;
    }
    .theme-toggle button[aria-pressed="true"] {
      background: var(--gradient-brand);
      color: #ffffff;
      box-shadow: 0 0 12px var(--accent-glow);
    }
    :host-context([data-theme="light"]) .theme-toggle button[aria-pressed="true"] {
      background: var(--accent);
      color: #ffffff;
      box-shadow: none;
    }
    .theme-toggle button:hover:not([aria-pressed="true"]) {
      color: var(--text-primary);
    }

    .status {
      font-size: 0.85rem;
      color: var(--text-muted);
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .status .dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: var(--color-success);
      box-shadow: 0 0 8px var(--color-success);
    }

    .active-llm-pill {
      all: unset;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      background: var(--bg-input);
      border: 1px solid var(--border-subtle);
      border-radius: 999px;
      padding: 4px 12px;
      font-size: 0.78rem;
      color: var(--text-secondary);
      transition: border-color 0.15s, color 0.15s;
    }
    .active-llm-pill:hover {
      border-color: var(--accent);
      color: var(--text-primary);
    }
    .pill-sep { color: var(--text-muted); }
    .pill-model { font-family: var(--font-mono); font-size: 0.74rem; }

    .icon-btn {
      background: transparent;
      color: var(--text-secondary);
      border: 1px solid var(--border-subtle);
      padding: 5px 12px;
      border-radius: 8px;
      font-family: inherit;
      font-size: 0.82rem;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .icon-btn:hover {
      color: var(--text-heading);
      border-color: var(--accent);
      background: var(--bg-card-hover);
    }
    .icon-btn.danger {
      color: var(--color-error);
      border-color: var(--color-error);
    }
    .icon-btn.danger:hover {
      background: rgba(255, 110, 110, 0.10);
      color: var(--color-error);
      border-color: var(--color-error);
    }
  `],
})
export class HeaderComponent {
  @Output() onSettings = new EventEmitter<void>();
  @Output() onHelp = new EventEmitter<void>();
  @Output() onQuit = new EventEmitter<void>();

  private themeService = inject(ThemeService);
  private settingsService = inject(SettingsService);

  readonly theme = this.themeService.theme;
  readonly active = this.settingsService.active;

  setTheme(theme: Theme): void {
    this.themeService.setTheme(theme);
  }
}
