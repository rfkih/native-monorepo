/**
 * quoteView — which live quote the till may SHOW, as a pure function.
 *
 * The quote query keeps its previous result as placeholder data so the total does not flicker
 * while a re-price is in flight. That is right while the cart has lines and wrong the moment it
 * has none: TanStack still hands the previous data back for a disabled query, so ringing an item
 * and then removing it left the deck showing the old total — and "Tagih Rp 45.000" — under an
 * empty list. Nothing to price means nothing to show, whatever the cache still holds.
 */
export function visibleQuote<T>(cartLineCount: number, data: T | undefined): T | null {
  if (cartLineCount === 0) return null
  return data ?? null
}
