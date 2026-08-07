/**
 * registerGate.ts — pure decision core for the "open the register first" flow (owner request:
 * "the POS should prompt to open the drawer before the first transaction, so sales never land
 * outside a session again"). Register sessions are RESTAURANT-ONLY (ADR 0036); this module is not
 * used by servicepos.
 *
 * Two call sites in Pos.tsx share this ONE decision:
 *   1. Entry prompt — auto-open the RegisterSheet once per till visit when there's no open session.
 *   2. Payment gate — block the pay action the same way, every tap (not just-once).
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
 * Used directly as the payment gate (every pay tap re-evaluates it) and as the base condition for
 * the once-per-visit entry prompt.
 */
export function noConfirmedOpenSession(state: RegisterQueryState): boolean {
  if (state.offline) return false
  if (state.isLoading || state.isError) return false
  return state.session === null
}

/**
 * The entry-prompt decision: auto-open the register sheet when there's no confirmed open session
 * AND it hasn't already been auto-prompted this till visit. `alreadyPrompted` is the caller's
 * ref/flag (a dismissed sheet must not re-pop — the payment gate is the real enforcement).
 */
export function shouldAutoPromptRegister(
  state: RegisterQueryState,
  alreadyPrompted: boolean,
): boolean {
  return !alreadyPrompted && noConfirmedOpenSession(state)
}
