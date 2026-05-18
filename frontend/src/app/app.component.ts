import { Component, HostListener, OnInit, ViewChild, inject, signal } from '@angular/core';
import { HeaderComponent } from './features/header/header.component';
import { SettingsModalComponent } from './features/settings/settings-modal.component';
import { InboxComponent } from './features/inbox/inbox.component';
import { WelcomeScreenComponent } from './features/welcome/welcome-screen.component';
import { HelpOverlayComponent } from './shared/help-overlay/help-overlay.component';
import { ConfirmDialogComponent } from './shared/confirm-dialog/confirm-dialog.component';
import { ChatPanelComponent } from './features/chat/chat-panel.component';
import { UpdateBannerComponent } from './features/header/update-banner.component';
import { SettingsService } from './core/settings.service';
import { ShutdownService } from './core/shutdown.service';
import { ChromeSessionService } from './core/chrome-session.service';
import { CacheService } from './core/cache.service';
import { EmailOpenerService } from './core/email-opener.service';

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
    HelpOverlayComponent, ConfirmDialogComponent, ChatPanelComponent,
    UpdateBannerComponent,
  ],
  template: `
    <im-header
      (onSettings)="settingsOpen.set(true)"
      (onHelp)="helpOpen.set(true)"
      (onClearCache)="clearCacheConfirmOpen.set(true)"
      (onLogout)="logoutConfirmOpen.set(true)"
      (onQuit)="quitConfirmOpen.set(true)"
    ></im-header>
    <im-update-banner></im-update-banner>

    <button type="button"
            class="chat-fab"
            (click)="toggleChat()"
            [class.active]="chatOpen()"
            [attr.aria-label]="chatOpen() ? 'Close chat' : 'Open chat with your inbox'"
            title="Chat with your inbox (c)">
      <span class="chat-fab__icon" aria-hidden="true">💬</span>
      <span class="chat-fab__label">Chat</span>
    </button>

    @if (chatOpen()) {
      <im-chat-panel
        (closed)="chatOpen.set(false)"
        (openEmail)="onChatOpenEmail($event)"
      ></im-chat-panel>
    }

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
    @if (clearCacheConfirmOpen()) {
      <im-confirm-dialog
        title="🗑 Clear all cached AI data?"
        [message]="clearCacheStatus() || 'This wipes every cached enrichment (summaries, badges, action items, draft replies), every raw scraped body, and every chat search embedding. Gmail credentials are NOT touched. The next time you click ▶ Process, everything is recomputed from scratch (LLM cost re-paid).'"
        confirmLabel="🗑 Clear cache"
        [danger]="true"
        (confirmed)="onClearCacheConfirmed()"
        (cancelled)="clearCacheConfirmOpen.set(false); clearCacheStatus.set(null)"
      ></im-confirm-dialog>
    }
    @if (logoutConfirmOpen()) {
      <im-confirm-dialog
        title="🔒 Log out of Chrome and wipe profile?"
        [message]="logoutStatus() || 'This closes the attached Chrome window and DELETES every saved password, cookie, autofill entry, cache file, and browsing history for the Intelli-mailbox profile. Next time you launch the app you will be back at Gmail\\'s sign-in screen. Use this before handing the machine over to someone else.'"
        confirmLabel="🔒 Log out & wipe"
        [danger]="true"
        (confirmed)="onLogoutConfirmed()"
        (cancelled)="logoutConfirmOpen.set(false)"
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
    .chat-fab {
      position: fixed;
      bottom: 22px;
      right: 22px;
      display: inline-flex;
      align-items: center;
      gap: 8px;
      height: 52px;
      padding: 0 20px 0 16px;
      border-radius: 26px;
      border: none;
      background: var(--gradient-brand);
      color: #ffffff;
      font-size: 0.95rem;
      font-weight: 600;
      letter-spacing: 0.01em;
      cursor: pointer;
      box-shadow: 0 6px 22px rgba(0, 0, 0, 0.30);
      transition: transform 0.15s ease, box-shadow 0.15s ease;
      z-index: 55;
    }
    .chat-fab__icon { font-size: 1.25rem; line-height: 1; }
    .chat-fab__label { line-height: 1; }
    .chat-fab:hover { transform: scale(1.04); }
    .chat-fab.active { transform: scale(0.96); }
  `],
})
export class AppComponent implements OnInit {
  private settingsService = inject(SettingsService);
  private shutdownService = inject(ShutdownService);
  private chromeSessionService = inject(ChromeSessionService);
  private cacheService = inject(CacheService);
  private emailOpener = inject(EmailOpenerService);

  readonly settingsOpen = signal<boolean>(false);
  readonly helpOpen = signal<boolean>(false);
  readonly quitConfirmOpen = signal<boolean>(false);
  readonly stopped = signal<boolean>(false);
  readonly chatOpen = signal<boolean>(false);
  readonly logoutConfirmOpen = signal<boolean>(false);
  /** When logout has completed and we want to show the result message back
   *  in the confirm dialog before the user dismisses it. Null while idle. */
  readonly logoutStatus = signal<string | null>(null);
  readonly clearCacheConfirmOpen = signal<boolean>(false);
  readonly clearCacheStatus = signal<string | null>(null);

  @ViewChild(ChatPanelComponent) private chatPanel?: ChatPanelComponent;
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

  toggleChat(): void {
    this.chatOpen.update(v => !v);
  }

  onChatOpenEmail(id: string): void {
    // Route through the typed EmailOpenerService — inbox component
    // subscribes to its stream and resolves the id against its items().
    this.emailOpener.request(id);
  }

  onQuitConfirmed(): void {
    this.quitConfirmOpen.set(false);
    this.shutdownService.fire().subscribe(() => {
      this.stopped.set(true);
    });
  }

  onClearCacheConfirmed(): void {
    this.cacheService.clear().subscribe({
      next: (resp) => {
        this.clearCacheStatus.set(resp.message);
        // Auto-dismiss + reload after 1.5s so the user sees the success
        // message but lands on a fresh inbox view immediately after.
        setTimeout(() => {
          this.clearCacheConfirmOpen.set(false);
          this.clearCacheStatus.set(null);
          window.location.reload();
        }, 1500);
      },
      error: (err) => {
        this.clearCacheStatus.set('Clear failed: ' + (err?.message || 'network error'));
      },
    });
  }

  onLogoutConfirmed(): void {
    // Close + wipe Chrome, then stop the app — the only way to *truly* zero
    // out the session is for the JVM to also drop the indexed vector store
    // we built up while signed in. Without the shutdown, a re-launch finds
    // wiped Chrome state but still has the prior owner's email summaries
    // in memory.
    this.chromeSessionService.logout().subscribe({
      next: (resp) => {
        this.logoutStatus.set(resp.message
          + (resp.ok ? ' Stopping the app now…' : ''));
        if (resp.ok) {
          this.shutdownService.fire().subscribe(() => {
            this.logoutConfirmOpen.set(false);
            this.stopped.set(true);
          });
        }
      },
      error: (err) => {
        this.logoutStatus.set('Logout failed: ' + (err?.message || 'network error'));
      },
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
    if (e.key === 'c' || e.key === 'C') {
      e.preventDefault();
      this.toggleChat();
      return;
    }
    if (e.key === 'Escape') {
      this.settingsOpen.set(false);
      this.helpOpen.set(false);
      this.quitConfirmOpen.set(false);
      this.chatOpen.set(false);
    }
  }

  private isTyping(target: EventTarget | null): boolean {
    if (!(target instanceof HTMLElement)) return false;
    const tag = target.tagName;
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
  }
}
