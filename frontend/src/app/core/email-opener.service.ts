import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';

/**
 * Cross-component bus for "open this email's detail modal" requests. The
 * chat panel emits an id when the user clicks a citation chip; the
 * inbox component subscribes and resolves the id against its current
 * items() list to open the modal. A typed service is preferred over a
 * window CustomEvent so the contract is discoverable, strongly-typed,
 * and survives in tests / SSR.
 */
@Injectable({ providedIn: 'root' })
export class EmailOpenerService {
  private readonly _open$ = new Subject<string>();

  /** Stream of email ids that should be opened in the detail modal. */
  readonly open$ = this._open$.asObservable();

  /** Request that {@code id}'s detail modal be opened. Consumers listening
   *  to {@link open$} (typically the inbox view) resolve the id locally. */
  request(id: string): void {
    if (!id) return;
    this._open$.next(id);
  }
}
