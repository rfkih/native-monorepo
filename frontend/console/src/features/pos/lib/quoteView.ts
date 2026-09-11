/**
 * quoteView — which live quote the till may SHOW, as a pure function.
 *
 * The quote query keeps its previous result as placeholder data so the total does not flicker
 * while a re-price is in flight. That is right while the cart has lines and wrong the moment it
 * has none: TanStack still hands the previous data back for a disabled query, so ringing an item
 * and then removing it left the deck showing the old total — and "Tagih Rp 45.000" — under an
 * empty list. Nothing to price means nothing to show, whatever the query cache still holds.
 * (A resumed parked order is a second cache with the same failure; Pos.tsx gates that one.)
 *
 * Returns `undefined`, not `null`: that is TanStack's own word for "no data", so the hook can hand
 * the result straight through as `data` without the type widening.
 */
export function visibleQuote<T>(cartLineCount: number, data: T | undefined): T | undefined {
  return cartLineCount === 0 ? undefined : data
}
