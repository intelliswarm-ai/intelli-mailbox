import { Component, HostListener, OnInit, inject, signal } from '@angular/core';
import { HeaderComponent } from './features/header/header.component';
import { SettingsModalComponent } from './features/settings/settings-modal.component';
import { InboxComponent } from './features/inbox/inbox.component';
import { WelcomeScreenComponent } from './features/welcome/welcome-screen.component';
import { HelpOverlayComponent } from './shared/help-overlay/help-overlay.component';
import { ConfirmDialogComponent } from './shared/confirm-dialog/confirm-dialog.component';
import { SettingsService } from './core/settings.service';
import { ShutdownService } from './core/shutdown.service';

/**
 * Root shell. Header is live (theme + active-LLM pill + ⚙ ? ⏻ buttons).
 * Settings, Help, and Quit dialogs open on demand. Inbox + digest views
 * land in steps 5–6.
 */
@Component({
  selector: 'im-app',
  standalone: true,
  imports: [
    HeaderComponent, SettingsModalComponent, InboxComponent, WelcomeScreenComponent,
    HelpOverlayComponent, ConfirmDialogComponent,
  ],
  template: `
    <im-header
      (onSettings)="settingsOpen.set(true)"
      (onHelp)="helpOpen.set(true)"
      (onQuit)="quitConfirmOpen.set(true)"
    ></im-header>

    @if (stopped()) {
      <main class="stopped">
        <div class="icon">⏻</div>
        <h2>Intelli-mailbox stopped</h2>
        <p>The app is no longer running. You can close this tab.</p>
        <p class="hint">
          To use Intelli-mailbox again, launch it from your Start Menu (Windows)
          or Applications folder (macOS / Linux).
        </p>
      </main>
    } @else if (!systemReady()) {
      <im-welcome-screen (ready)="onSystemReady()"></im-welcome-screen>
    } @else {
      <im-inbox></im-inbox>
    }

    @if (settingsOpen()) {
      <im-settings-modal (closed)="settingsOpen.set(false)"></im-settings-modal>
    }
    @if (helpOpen()) {
      <im-help-overlay (closed)="helpOpen.set(false)"></im-help-overlay>
    }
    @if (quitConfirmOpen()) {
      <im-confirm-dialog
        title="⏻ Quit Intelli-mailbox?"
        message="This stops the app entirely — closes the local server and frees the AI from memory. You'll need to relaunch from the Start Menu (Windows) or Applications folder (macOS / Linux) to use it again."
        confirmLabel="⏻ Quit"
        [danger]="true"
        (confirmed)="onQuitConfirmed()"
        (cancelled)="quitConfirmOpen.set(false)"
      ></im-confirm-dialog>
    }
  `,
  styles: [`
    main {
      max-width: 720px;
      margin: 60px auto;
      padding: 0 24px;
      text-align: center;
    }
    main.stopped {
      min-height: calc(100vh - 80px);
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
    }
    main.stopped .icon {
      font-size: 3rem;
      margin-bottom: 12px;
    }
    h2 {
      font-size: 1.6rem;
      font-weight: 600;
      letter-spacing: -0.01em;
      margin: 0 0 12px;
      color: var(--text-heading);
    }
    p {
      color: var(--text-secondary);
      margin: 0 0 8px;
      line-height: 1.55;
    }
    p.hint {
      color: var(--text-muted);
      font-size: 0.9rem;
      margin-top: 16px;
    }
    kbd {
      background: var(--bg-input);
      border: 1px solid var(--border-subtle);
      border-radius: 4px;
      padding: 1px 6px;
      font-family: var(--font-mono);
      font-size: 0.78rem;
      color: var(--text-primary);
    }
  `],
})
export class AppComponent implements OnInit {
  private settingsService = inject(SettingsService);
  private shutdownService = inject(ShutdownService);

  readonly settingsOpen = signal<boolean>(false);
  readonly helpOpen = signal<boolean>(false);
  readonly quitConfirmOpen = signal<boolean>(false);
  readonly stopped = signal<boolean>(false);
  /** Welcome-screen → inbox handoff. Stays false until diagnostics pass
   *  (or the user clicks "Start now"); persists for the session so revisiting
   *  doesn't bounce the user back to the system check. */
  readonly systemReady = signal<boolean>(false);

  ngOnInit(): void {
    this.settingsService.load().subscribe({ error: () => { /* fail silently — pill stays hidden */ } });
  }

  onSystemReady(): void {
    this.systemReady.set(true);
  }

  onQuitConfirmed(): void {
    this.quitConfirmOpen.set(false);
    this.shutdownService.fire().subscribe(() => {
      this.stopped.set(true);
    });
  }

  /**
   * Global hotkeys. Only "?" for the help overlay lands in step 4 — the
   * rest (j/k/r/etc) wire up alongside the inbox view in step 5. Skip
   * when the user is typing in an input or any modal is already open.
   */
  @HostListener('document:keydown', ['$event'])
  onKeyDown(e: KeyboardEvent): void {
    if (this.isTyping(e.target)) return;
    if (e.key === '?') {
      e.preventDefault();
      this.helpOpen.set(true);
      return;
    }
    if (e.key === 'Escape') {
      this.settingsOpen.set(false);
      this.helpOpen.set(false);
      this.quitConfirmOpen.set(false);
    }
  }

  private isTyping(target: EventTarget | null): boolean {
    if (!(target instanceof HTMLElement)) return false;
    const tag = target.tagName;
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
  }
}
