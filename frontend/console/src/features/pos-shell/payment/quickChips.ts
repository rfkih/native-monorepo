/**
 * quickChips.ts — quick-cash chip amounts, extracted from PaymentModal.tsx /
 * BillPaymentModal.tsx / ServicePaymentModal.tsx (redesign P1 — the three copies were
 * byte-identical).
 *
 * First file of features/pos-shell — the shared, VERTICAL-AGNOSTIC POS presentation layer.
 * Rule for everything under pos-shell/: stateless/pure presentation only — no hooks, no
 * fetching, no mutations. State and API calls stay in the vertical features (pos/, servicepos/).
 */

// IDR banknote steps (minor units = whole rupiah, exponent 0 — rule 8). Chips are the total
// rounded UP to the next multiple of each step — the amounts a customer actually hands over
// (UX audit: the old static 50k/100k presets left any bill above Rp100k with only "Exact").
const IDR_NOTE_STEPS = [5_000, 10_000, 20_000, 50_000, 100_000] as const

/** Build quick-cash chip options: [exact, ...round-ups] all as minor units. */
export function quickChips(totalMinor: number, currency: string): number[] {
  if (currency === 'IDR') {
    const ups = new Set<number>()
    for (const step of IDR_NOTE_STEPS) {
      // Small steps are noise on a big bill (Rp10k rounding on Rp3jt) — except 100k, the
      // largest real banknote, which always yields the natural "stack of hundreds" amount.
      if (step * 20 < totalMinor && step !== 100_000) continue
      const up = Math.ceil(totalMinor / step) * step
      if (up > totalMinor) ups.add(up)
    }
    return [totalMinor, ...[...ups].sort((a, b) => a - b).slice(0, 3)]
  }
  // For non-IDR: exact + a couple of round-up multiples of 500 minor units (e.g. USD cents)
  const round500 = Math.ceil(totalMinor / 500) * 500
  const round1000 = Math.ceil(totalMinor / 1000) * 1000
  const chips = [totalMinor]
  if (round500 > totalMinor) chips.push(round500)
  if (round1000 > round500) chips.push(round1000)
  return chips
}
