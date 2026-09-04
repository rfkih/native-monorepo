/**
 * stocktakeVarianceGuard.ts — the stock-opname variance-confirmation guard (ADR 0068 part 3). Pure
 * logic (no react-query / no DOM), the SAME shape as ../../pos/lib/closeGuard.ts: a fat-finger safety
 * net that pauses submit for an "are you sure?" confirm when a counted quantity looks implausible.
 * The server stays authoritative either way (it recomputes system/counted/variance and posts
 * whatever is submitted) — this only decides whether to show the confirm step.
 *
 * Direct fix for the real incident ADR 0068 documents: a stock opname counted `Daging Kebab TIS
 * FOOD` (unit gram) at 2,640,000 g (2.64 tonnes) against a system quantity of 3,411 g — a kg→g
 * slip — which posted a ~Rp 160,831,929 "gain" that phantom-profited the P&L. Two INDEPENDENT trip
 * wires, either of which flags a line:
 *
 *  1. RATIO — counted and system are more than IMPLAUSIBLE_RATIO apart (either direction) AND the
 *     raw gap clears MIN_ABSOLUTE_DELTA_QTY base units. Catches the classic ×1000 g/kg slip (or its
 *     mirror — a count typed 1000× too SMALL, e.g. a decimal-kg count dropped into a whole-gram
 *     field) without tripping on an ordinary big top-up of a near-empty item, e.g. system 5 → counted
 *     200 pcs is a 40× ratio on a tiny absolute gap — not what this guard is for.
 *  2. VALUE — when the ingredient carries a unit cost, the variance VALUE (|counted − system| ×
 *     unitCostMinor) exceeds a currency-scaled threshold. Catches an implausible count the ratio
 *     guard misses (a "only" ~10× slip on an expensive ingredient) and, for a first count of a
 *     brand-new ingredient (systemQty 0, where a ratio is undefined), is the only trip wire that can
 *     fire — see the skip below.
 *
 * Both are tuned to stay silent on normal shrinkage/restock; a false positive costs one extra tap
 * ("Save anyway") — this is a safety net, not a hard block (fail OPEN, ADR 0068 §3 "Consequences").
 * Money (rule 8): unitCostMinor/thresholds are integer minor units, never a float.
 */
import { isoMinorExponent } from '@/lib/money'

// --- Trip wire 1: order-of-magnitude ratio --------------------------------------------------------
// A count at least 100× the system quantity (or vice-versa) sits squarely in "wrong unit/extra
// zeros" territory. 100×, not 1000×, gives margin so a slip diluted by some genuine usage/restock
// since the last count (e.g. a true ~500× slip) is still caught.
const IMPLAUSIBLE_RATIO = 100

// The ratio alone isn't enough: near a near-zero system quantity almost ANY restock reads as a huge
// multiple (system 2 → counted 250 is a 125× ratio, yet a totally ordinary top-up). Requiring the
// raw gap to also clear this many BASE units (g/ml/pcs/pack) keeps the guard silent on small stock,
// while a real ×1000 slip (a gap in the thousands-to-millions of base units) clears it easily.
const MIN_ABSOLUTE_DELTA_QTY = 1_000

// --- Trip wire 2: variance value -------------------------------------------------------------------
// Major-unit variance-value threshold, per currency — well above ordinary single-line shrinkage for
// a small business, well below the incident this guard exists to catch (~Rp 160 juta). Keyed like
// lib/money.ts's ISO_MINOR_EXPONENT table; an unlisted currency falls back to the DEFAULT below.
const VALUE_THRESHOLD_MAJOR: Record<string, number> = {
  IDR: 5_000_000, // Rp 5 juta
  USD: 500, // $500
}
const DEFAULT_VALUE_THRESHOLD_MAJOR = 500

function valueThresholdMinor(currency: string): number {
  const majorThreshold = VALUE_THRESHOLD_MAJOR[currency] ?? DEFAULT_VALUE_THRESHOLD_MAJOR
  return majorThreshold * 10 ** isoMinorExponent(currency)
}

/** One opname line as the guard sees it — plain numbers, BASE unit (g/ml/pcs/pack), no display
 * formatting or ingredient metadata; the caller looks up name/unit for the confirm dialog by
 * `ingredientId` from whatever it already has loaded. */
export interface StocktakeVarianceLine {
  ingredientId: string
  /** On-hand quantity before this count, in the ingredient's BASE unit. */
  systemQty: number
  /** The operator's physical count, in the ingredient's BASE unit (already converted from any
   * kg/liter display value — the same base-unit number the submit payload carries). */
  countedQty: number
  /** Per-BASE-unit cost in minor units; null = uncosted (only the RATIO trip wire can fire). */
  unitCostMinor: number | null
  /** The ingredient's cost currency — required only to scale the VALUE threshold, so any value
   * works when `unitCostMinor` is null. Callers pass the ingredient's own currency, already
   * defaulted to the company base currency upstream (this helper never guesses one). */
  currency: string
}

export type StocktakeVarianceReason = 'ratio' | 'value'

export interface StocktakeVarianceFlag {
  ingredientId: string
  systemQty: number
  countedQty: number
  /** Which trip wire(s) fired — 'ratio' = order-of-magnitude gap, 'value' = variance value over the
   * threshold. A line can carry both. */
  reasons: StocktakeVarianceReason[]
}

/**
 * Runs both trip wires over every line and returns only the ones that fired — an empty array means
 * the count needs no confirmation and submit can proceed straight through.
 */
export function checkStocktakeVariance(lines: StocktakeVarianceLine[]): StocktakeVarianceFlag[] {
  const flags: StocktakeVarianceFlag[] = []

  for (const line of lines) {
    const { ingredientId, systemQty, countedQty, unitCostMinor, currency } = line
    const absDelta = Math.abs(countedQty - systemQty)
    const reasons: StocktakeVarianceReason[] = []

    // Ratio undefined (division by zero) when either side is 0 — a brand-new ingredient's first
    // count, or a physical count of exactly nothing, is not itself implausible; the VALUE trip wire
    // is the only one that can flag those (see the class doc above).
    if (systemQty > 0 && countedQty > 0 && absDelta >= MIN_ABSOLUTE_DELTA_QTY) {
      const ratio = Math.max(systemQty, countedQty) / Math.min(systemQty, countedQty)
      if (ratio >= IMPLAUSIBLE_RATIO) reasons.push('ratio')
    }

    if (unitCostMinor != null) {
      const varianceValueMinor = absDelta * unitCostMinor
      if (varianceValueMinor >= valueThresholdMinor(currency)) reasons.push('value')
    }

    if (reasons.length > 0) flags.push({ ingredientId, systemQty, countedQty, reasons })
  }

  return flags
}
