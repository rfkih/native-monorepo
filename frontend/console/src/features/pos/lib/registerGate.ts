/**
 * registerGate.ts — pure decision core for the "open the register first" flow (owner request:
 * "the POS should prompt to open the drawer before the first transaction, so sales never land
 * outside a session again"). Register sessions are RESTAURANT-ONLY (ADR 0036); this module is not
 * used by servicepos.
 *
 * One call site in Pos.tsx: the payment gate — block the pay action and route to the
 * RegisterSheet instead, re-evaluated on every tap. There is deliberately NO entry auto-prompt
 * (owner feedback: the sheet popping open the moment the till loads on a new day reads as a
 * forced "closing kasir"); the pay tap is when a session genuinely becomes necessary.
 *
 * The decision only fires with CONFIDENCE: online, and the current-session query has SETTLED
 * (neither loading nor errored) with a definite null (the server's 204 "no open session"). Loading
 * or errored states — and offline — always fail OPEN (let the sale proceed): a flaky read must
 * never block a sale, and the server remains the source of truth for money (ADR 0028 — offline is
 * exempt end-to-end since register mutations are already disabled offline).
 */

export interface RegisterQueryState {
  /** True while offline — the whole flow (prompt + gate) is a no-op offline. */
  offline: boolean
  /** The current-session query's loading flag (react-query `isLoading`). */
  isLoading: boolean
  /** The current-session query's error flag (react-query `isError`). */
  isError: boolean
  /** The resolved session: null = confirmed no open session (server 204). undefined = not
   * resolved yet. Anything else (an object) = an open session exists. */
  session: unknown | null | undefined
}

/**
 * True only when we can say WITH CONFIDENCE that there is no open register session right now.
 * Used directly as the payment gate (every pay tap re-evaluates it).
 */
export function noConfirmedOpenSession(state: RegisterQueryState): boolean {
  if (state.offline) return false
  if (state.isLoading || state.isError) return false
  return state.session === null
}

/** The three i18n keys the till-menu register entry can carry, chosen by the drawer's state. */
export type RegisterMenuLabelKey = 'register.titleClose' | 'register.title' | 'register.titleOpen'

/**
 * The till-menu register entry's label key, reflecting the drawer's ACTUAL state (owner request
 * "kalo sudah closing harusnya berubah jadi buka kasir"): a CONFIRMED open session offers to close
 * it ("Closing kasir" = titleClose); a confirmed-closed drawer (server 204 → null) offers to open
 * it ("Buka kasir" = titleOpen); while the read is still loading or has errored, keep the neutral
 * combined label rather than guess the wrong action. Unlike the payment gate this is display-only,
 * so offline is irrelevant here — the menu item's own `disabled` already covers offline.
 */
export function registerMenuLabelKey(
  state: Pick<RegisterQueryState, 'isLoading' | 'isError' | 'session'>,
): RegisterMenuLabelKey {
  if (state.session != null) return 'register.titleClose'
  if (state.isLoading || state.isError) return 'register.title'
  return 'register.titleOpen'
}
