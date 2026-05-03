import { Component, EventEmitter, Input, Output } from '@angular/core';

/**
 * Reusable in-app confirm dialog. Replaces the native browser confirm() —
 * Chrome silently returns false from confirm() in some focus / "prevent
 * additional dialogs" states, leaving destructive flows looking dead.
 *
 * Caller sets the open flag, listens for (confirmed) / (cancelled),
 * provides title + message + button labels.
 */
@Component({
  selector: 'im-confirm-dialog',
  standalone: true,
  template: `
    <div class="backdrop" (click)="onCancel()">
      <div class="card" (click)="$event.stopPropagation()">
        <h3>{{ title }}</h3>
        <p>{{ message }}</p>
        <div class="actions">
          <button type="button" class="secondary" (click)="onCancel()">{{ cancelLabel }}</button>
          <button type="button"
                  class="confirm"
                  [class.danger]="danger"
                  (click)="onConfirm()">{{ confirmLabel }}</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .backdrop {
      position: fixed; inset: 0; z-index: 110;
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
      max-width: 480px; width: 92%;
      color: var(--text-primary);
      box-shadow: 0 20px 60px rgba(0, 0, 0, 0.4);
    }
    h3 {
      margin: 0 0 12px;
      font-size: 1.05rem; font-weight: 600;
      color: var(--text-heading);
    }
    p {
      margin: 0 0 20px;
      color: var(--text-secondary);
      font-size: 0.92rem; line-height: 1.5;
    }
    .actions {
      display: flex; gap: 8px; justify-content: flex-end;
    }
    .actions button {
      padding: 8px 16px; border-radius: 8px;
      font-family: inherit; font-size: 0.85rem; font-weight: 500;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .actions .secondary {
      background: transparent;
      color: var(--text-secondary);
      border: 1px solid var(--border-subtle);
    }
    .actions .secondary:hover {
      color: var(--text-heading);
      border-color: var(--accent);
      background: var(--bg-card-hover);
    }
    .actions .confirm {
      background: var(--accent-dim);
      color: var(--accent);
      border: 1px solid var(--accent);
    }
    .actions .confirm:hover {
      background: var(--gradient-brand);
      color: #ffffff;
      border-color: var(--accent-2);
    }
    .actions .confirm.danger {
      background: rgba(255, 110, 110, 0.10);
      color: var(--color-error);
      border-color: var(--color-error);
    }
    .actions .confirm.danger:hover {
      background: var(--color-error);
      color: #ffffff;
      box-shadow: 0 0 12px rgba(255, 110, 110, 0.4);
    }
  `],
})
export class ConfirmDialogComponent {
  @Input() title = 'Confirm';
  @Input() message = '';
  @Input() confirmLabel = 'OK';
  @Input() cancelLabel = 'Cancel';
  @Input() danger = false;

  @Output() confirmed = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();

  onConfirm(): void { this.confirmed.emit(); }
  onCancel(): void { this.cancelled.emit(); }
}
