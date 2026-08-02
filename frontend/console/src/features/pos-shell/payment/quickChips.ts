/**
 * quickChips.ts — quick-cash chip amounts, extracted from PaymentModal.tsx /
 * BillPaymentModal.tsx / ServicePaymentModal.tsx (redesign P1 — the three copies were
 * byte-identical).
 *
 * First file of features/pos-shell — the shared, VERTICAL-AGNOSTIC POS presentation layer.
 * Rule for everything under pos-shell/: stateless/pure presentation only — no hooks, no
 * fetching, no mutations. State and API calls stay in the vertical features (pos/, servicepos/).
 */

// Quick-cash chip amounts in IDR minor units (= whole rupiah, exponent 0 — rule 8).
// For non-IDR we build chips relative to the total (exact + round-up multiples).
const IDR_QUICK_CHIPS = [50_000, 100_000] as const

/** Build quick-cash chip options: [exact, ...preset-overs] all as minor units. */
export function quickChips(totalMinor: number, currency: string): number[] {
  if (currency === 'IDR') {
    // Exact + preset IDR chips that exceed the total
    return [totalMinor, ...IDR_QUICK_CHIPS.filter((v) => v > totalMinor)]
  }
  // For non-IDR: exact + a couple of round-up multiples of 500 minor units (e.g. USD cents)
  const round500 = Math.ceil(totalMinor / 500) * 500
  const round1000 = Math.ceil(totalMinor / 1000) * 1000
  const chips = [totalMinor]
  if (round500 > totalMinor) chips.push(round500)
  if (round1000 > round500) chips.push(round1000)
  return chips
}
