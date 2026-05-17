import {
  ChangeDetectionStrategy, Component, ElementRef, EventEmitter, Output,
  ViewChild, inject, signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ChatService, ChatCitation } from '../../core/chat.service';

interface ChatTurn {
  role: 'user' | 'assistant' | 'error';
  text: string;
  citations?: ChatCitation[];
  ts: number;
}

/**
 * Slide-out chat dock — the user types a question, the backend runs RAG +
 * tool calling, and the answer renders with email-id chips that emit
 * {@code openEmail} so the parent can open the detail modal.
 *
 * <p>Conversation history is in-memory only (this component's signal). On
 * close + reopen the thread persists for the session; on full page reload it
 * resets — by design, since the backend is single-turn for now and
 * re-replaying the prompt would re-pay LLM cost without giving the model
 * the prior turn's reasoning.
 */
@Component({
  selector: 'im-chat-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, FormsModule],
  template: `
    <aside class="dock" [class.collapsed]="!open()">
      <header class="dock-header">
        <span class="title">💬 Chat with your inbox</span>
        <button type="button"
                class="icon"
                (click)="closed.emit()"
                title="Close (Esc)">✕</button>
      </header>

      <div class="thread" #thread>
        @if (turns().length === 0) {
          <div class="empty">
            <p>Ask anything about your inbox.</p>
            <ul class="suggestions">
              <li><button type="button" (click)="quickAsk('What do I need to reply to today?')">
                What do I need to reply to today?
              </button></li>
              <li><button type="button" (click)="quickAsk('Show me everything tagged FINANCE this week')">
                Show me everything tagged FINANCE this week
              </button></li>
              <li><button type="button" (click)="quickAsk('What did I miss while I was offline?')">
                What did I miss while I was offline?
              </button></li>
            </ul>
          </div>
        }
        @for (turn of turns(); track turn.ts) {
          <div class="turn" [class.user]="turn.role === 'user'"
                            [class.assistant]="turn.role === 'assistant'"
                            [class.error]="turn.role === 'error'">
            <div class="bubble">
              <p class="text">{{ renderText(turn.text) }}</p>
              @if (turn.citations?.length) {
                <div class="cites">
                  @for (c of turn.citations; track c.id) {
                    <button type="button"
                            class="chip"
                            (click)="openEmail.emit(c.id)"
                            [title]="(c.sender || '(unknown sender)') + ' — ' + (c.subject || '(no subject)')">
                      ✉ {{ c.subject || c.id }}
                    </button>
                  }
                </div>
              }
            </div>
          </div>
        }
        @if (busy()) {
          <div class="turn assistant">
            <div class="bubble pending">Thinking… <span class="dots">•••</span></div>
          </div>
        }
      </div>

      <form class="composer" (ngSubmit)="send()">
        <textarea
          [(ngModel)]="draft"
          name="message"
          placeholder="Ask about your inbox… (Enter to send, Shift+Enter for newline)"
          rows="2"
          [disabled]="busy()"
          (keydown)="onKey($event)"></textarea>
        <button type="submit"
                [disabled]="busy() || !draft().trim()"
                class="send">Send</button>
      </form>
    </aside>
  `,
  styles: [`
    .dock {
      position: fixed;
      top: 0; right: 0; bottom: 0;
      width: min(440px, 100vw);
      background: var(--bg-card);
      border-left: 1px solid var(--border-section);
      box-shadow: -8px 0 32px rgba(0, 0, 0, 0.28);
      display: flex; flex-direction: column;
      z-index: 60;
      transform: translateX(0);
      transition: transform 0.18s ease;
    }
    .dock.collapsed { transform: translateX(100%); }
    .dock-header {
      padding: 14px 16px;
      display: flex; align-items: center;
      border-bottom: 1px solid var(--border-subtle);
      background: var(--bg-header);
    }
    .title {
      flex: 1; font-weight: 600; font-size: 0.95rem;
      color: var(--text-heading);
    }
    .icon {
      all: unset; cursor: pointer;
      color: var(--text-muted); font-size: 1rem;
      padding: 4px 8px; border-radius: 6px;
    }
    .icon:hover { color: var(--text-primary); background: var(--bg-card-hover); }

    .thread {
      flex: 1; overflow-y: auto;
      padding: 14px 14px 8px;
      display: flex; flex-direction: column; gap: 10px;
    }
    .empty {
      color: var(--text-muted);
      margin-top: 20px;
      text-align: center;
    }
    .empty p { margin: 0 0 12px; font-size: 0.9rem; }
    .suggestions {
      list-style: none; padding: 0; margin: 0;
      display: flex; flex-direction: column; gap: 6px;
    }
    .suggestions button {
      all: unset; cursor: pointer;
      background: var(--bg-input);
      border: 1px solid var(--border-subtle);
      border-radius: 8px;
      padding: 8px 12px;
      font-size: 0.82rem;
      color: var(--text-secondary);
      text-align: left;
      transition: border-color 0.15s, color 0.15s;
    }
    .suggestions button:hover {
      border-color: var(--accent);
      color: var(--text-primary);
    }

    .turn { display: flex; }
    .turn.user { justify-content: flex-end; }
    .turn .bubble {
      max-width: 86%;
      padding: 10px 12px;
      border-radius: 10px;
      font-size: 0.88rem;
      line-height: 1.45;
      background: var(--bg-input);
      color: var(--text-primary);
      border: 1px solid var(--border-subtle);
    }
    .turn.user .bubble {
      background: var(--gradient-brand);
      color: #ffffff;
      border-color: transparent;
    }
    .turn.error .bubble {
      background: rgba(255, 110, 110, 0.10);
      border-color: var(--color-error);
      color: var(--color-error);
    }
    .bubble .text {
      margin: 0;
      white-space: pre-wrap;
      word-wrap: break-word;
    }
    .bubble.pending { color: var(--text-muted); font-style: italic; }
    .bubble.pending .dots {
      letter-spacing: 2px;
      animation: pulse 1.2s ease-in-out infinite;
    }
    @keyframes pulse { 0%,100% { opacity: 0.35; } 50% { opacity: 1; } }

    .cites {
      display: flex; flex-wrap: wrap; gap: 6px;
      margin-top: 8px;
    }
    .chip {
      all: unset; cursor: pointer;
      background: var(--bg-card);
      border: 1px solid var(--border-subtle);
      border-radius: 999px;
      padding: 3px 10px;
      font-size: 0.74rem;
      color: var(--text-secondary);
      max-width: 240px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      transition: border-color 0.15s;
    }
    .chip:hover { border-color: var(--accent); color: var(--text-primary); }
    .turn.user .chip {
      background: rgba(255,255,255,0.16);
      border-color: transparent;
      color: #ffffff;
    }

    .composer {
      display: flex; gap: 8px;
      padding: 10px 12px;
      border-top: 1px solid var(--border-subtle);
      background: var(--bg-header);
    }
    .composer textarea {
      flex: 1;
      resize: none;
      background: var(--bg-input);
      border: 1px solid var(--border-subtle);
      border-radius: 8px;
      padding: 8px 10px;
      color: var(--text-primary);
      font-family: inherit;
      font-size: 0.88rem;
      line-height: 1.4;
      outline: none;
    }
    .composer textarea:focus { border-color: var(--accent); }
    .composer .send {
      all: unset; cursor: pointer;
      align-self: flex-end;
      background: var(--gradient-brand);
      color: #ffffff;
      font-size: 0.85rem;
      font-weight: 500;
      padding: 8px 16px;
      border-radius: 8px;
    }
    .composer .send:disabled {
      opacity: 0.45; cursor: not-allowed;
      box-shadow: none;
    }
  `],
})
export class ChatPanelComponent {
  @Output() closed = new EventEmitter<void>();
  /** Emits the email id (e.g. "1:3") the user clicked in a citation chip. */
  @Output() openEmail = new EventEmitter<string>();

  @ViewChild('thread') private threadEl?: ElementRef<HTMLDivElement>;

  private chatService = inject(ChatService);

  readonly open = signal<boolean>(true);
  readonly busy = signal<boolean>(false);
  readonly turns = signal<ChatTurn[]>([]);
  readonly draft = signal<string>('');

  show(): void { this.open.set(true); }
  hide(): void { this.open.set(false); }
  toggle(): void { this.open.update(v => !v); }

  onKey(e: KeyboardEvent): void {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      this.send();
    }
  }

  quickAsk(text: string): void {
    this.draft.set(text);
    this.send();
  }

  send(): void {
    const text = this.draft().trim();
    if (!text || this.busy()) return;
    this.draft.set('');
    this.appendTurn({ role: 'user', text, ts: Date.now() });
    this.busy.set(true);
    this.chatService.ask(text).subscribe({
      next: (resp) => {
        this.busy.set(false);
        if (!resp.ok) {
          this.appendTurn({
            role: 'error',
            text: resp.error || 'Something went wrong.',
            ts: Date.now(),
          });
          return;
        }
        this.appendTurn({
          role: 'assistant',
          text: resp.answer || '(empty answer)',
          citations: resp.citations || [],
          ts: Date.now(),
        });
      },
      error: (err) => {
        this.busy.set(false);
        this.appendTurn({
          role: 'error',
          text: err?.message || 'Network error — is the app still running?',
          ts: Date.now(),
        });
      },
    });
  }

  /** Strip internal id markers (`id=9:19`, `id=ab12cd34ef56`, etc.) from the
   *  prose before display. The model is asked to emit them so the backend
   *  can build the citations array, but they're meaningless to the user —
   *  the clickable chips below the answer expose the same emails better. */
  renderText(text: string): string {
    if (!text) return '';
    return text
      // Match `id=<token>` with surrounding punctuation/whitespace. Token can
      // be a stable hex hash (16 chars), a legacy "page:idx", or a bare numeric.
      // [A-Za-z0-9:-] covers all three; the bracketed group also tolerates
      // markdown bold (`**id=…**`) the small Ollama models occasionally emit.
      .replace(/\s*[\(\[]?\s*\*{0,2}\s*id\s*=\s*[A-Za-z0-9:-]+\*{0,2}\s*[\)\]]?\s*[:,.]?\s*/gi, ' ')
      // Collapse the whitespace runs the replacement may leave behind.
      .replace(/[ \t]{2,}/g, ' ')
      .replace(/ +([,.;:!?])/g, '$1')
      .trim();
  }

  private appendTurn(t: ChatTurn): void {
    this.turns.update(arr => [...arr, t]);
    queueMicrotask(() => {
      const el = this.threadEl?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    });
  }
}
