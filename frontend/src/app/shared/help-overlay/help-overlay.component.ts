import { Component, EventEmitter, Output } from '@angular/core';

/**
 * Keyboard-shortcuts overlay. Triggered by clicking ? in the header or
 * pressing the ? key. Click backdrop or Esc to dismiss.
 *
 * The shortcut list mirrors the legacy UI; entries that target features
 * not yet ported (j/k row navigation, view-switching) stay listed so the
 * help text stays consistent — the actual hotkeys land alongside their
 * features in steps 5–6.
 */
@Component({
  selector: 'im-help-overlay',
  standalone: true,
  template: `
    <div class="backdrop" (click)="onClose()">
      <div class="card" (click)="$event.stopPropagation()">
        <h3>Keyboard shortcuts</h3>
        <table>
          <tr><td class="kbd-cell"><kbd>r</kbd></td><td>Refresh inbox</td></tr>
          <tr><td class="kbd-cell"><kbd>i</kbd> / <kbd>d</kbd> / <kbd>s</kbd></td><td>Switch to Inbox / Digest / Suggestions view</td></tr>
          <tr><td class="kbd-cell"><kbd>j</kbd> / <kbd>k</kbd></td><td>Next / previous row</td></tr>
          <tr><td class="kbd-cell"><kbd>Enter</kbd></td><td>Open selected email</td></tr>
          <tr><td class="kbd-cell"><kbd>Esc</kbd></td><td>Close modal / clear selection</td></tr>
          <tr><td class="kbd-cell"><kbd>⌘K</kbd> / <kbd>/</kbd></td><td>Focus search</td></tr>
          <tr><td class="kbd-cell"><kbd>?</kbd></td><td>Show this overlay</td></tr>
        </table>
        <div class="close-hint">click anywhere to dismiss</div>
      </div>
    </div>
  `,
  styles: [`
    .backdrop {
      position: fixed; inset: 0; z-index: 100;
      background: rgba(0, 0, 0, 0.55);
      display: flex; align-items: center; justify-content: center;
      animation: fadein 0.15s ease;
    }
    @keyframes fadein { from { opacity: 0; } to { opacity: 1; } }

    .card {
      background: var(--bg-modal);
      border: 1px solid var(--border-subtle);
      border-radius: 12px;
      padding: 24px 28px;
      max-width: 460px; width: 90%;
      color: var(--text-primary);
      box-shadow: 0 20px 60px rgba(0, 0, 0, 0.4);
    }
    h3 {
      margin: 0 0 14px;
      font-size: 1rem; color: var(--text-heading);
    }
    table {
      width: 100%; border-collapse: collapse; font-size: 0.85rem;
    }
    td {
      padding: 4px 0; vertical-align: top;
    }
    td.kbd-cell {
      width: 90px; font-family: var(--font-mono);
    }
    td.kbd-cell kbd {
      background: var(--bg-input);
      border: 1px solid var(--border-subtle);
      border-radius: 4px;
      padding: 1px 6px;
      font-size: 0.78rem;
      color: var(--text-primary);
    }
    .close-hint {
      margin-top: 14px;
      font-size: 0.78rem;
      color: var(--text-muted);
      text-align: right;
    }
  `],
})
export class HelpOverlayComponent {
  @Output() closed = new EventEmitter<void>();

  onClose(): void { this.closed.emit(); }
}
