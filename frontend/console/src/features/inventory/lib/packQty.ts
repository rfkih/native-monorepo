/**
 * packQty.ts — the "packs sold, contents counted" quantity maths shared by every purchase surface
 * that lets a vendor sell BY THE PACK while inventory counts CONTENTS (a receipt says "TORTILLA 1
 * PCS" for a pack of 20 individual tortillas; a receipt says "TEPUNG 2 SAK" for two 25 kg sacks).
 *
 * Extracted (code-review F1, 2026-09-04) from `features/ap/lib/ingredientLink.ts` and
 * `features/expenses/lib/companyExpenseForm.ts`, which had byte-identical copies of this function
 * and type — two copies of the "an inflated pack size silently inflates stock" guard meant a future
 * fix could land in one form only. This is inventory maths, so it sits next to `units.ts`; both
 * purchase-line modules re-export it so their own call sites (`NewBill.tsx`, `NewCompanyExpense.tsx`)
 * are untouched.
 */
import { parseShownQtyInput, type UnitBearing } from './units'

/** The resolved BASE quantity, plus the pack count when pack mode was used (for the UI's "packs ×
 *  size = result" readback — the typo safety net). `packs: null` = pack mode wasn't used at all. */
export interface PackedQty {
  packs: number | null
  qtyBase: number
}

/**
 * Resolves a quantity input (+ optional pack-size input) to the BASE integer quantity. BLANK
 * `packSizeInput` (default): `qtyInput` is a plain display-unit quantity, parsed exactly like any
 * other quantity field. NON-BLANK `packSizeInput`: `qtyInput` now counts PACKS (always a whole
 * number — you don't buy half a pack), `packSizeInput` is how many of the ingredient's DISPLAY unit
 * are in ONE pack (decimal allowed for kg/liter, e.g. "2.5" kg/pack; whole for pcs/pack — the SAME
 * fraction rule as any other quantity input, via `parseShownQtyInput`), and the two multiply to the
 * BASE integer. Deliberately separate from `units.ts`'s `shownFactor`/`DISPLAY_OVER_BASE` (a fixed
 * 1000× kg/g, liter/ml family) — a pack size is an arbitrary per-product number.
 *
 * Returns `null` when either input fails to resolve: an under/mistyped pack size or count BLOCKS the
 * line rather than silently falling back to "no pack" or a wrong count (an inflated pack size
 * silently inflates stock, and only a stock opname would catch it later — this must fail loudly
 * instead).
 */
export function parsePackedQtyBase(
  qtyInput: string,
  packSizeInput: string,
  ingredient: UnitBearing,
): PackedQty | null {
  const packSizeTrimmed = packSizeInput.trim()
  if (packSizeTrimmed === '') {
    const qtyBase = parseShownQtyInput(qtyInput, ingredient)
    if (qtyBase == null || qtyBase <= 0) return null
    return { packs: null, qtyBase }
  }
  // Pack SIZE follows the ingredient's own display-unit rules — decimal allowed for kg/liter
  // (e.g. "2.5" kg per pack), whole for pcs/pack — exactly like any other quantity input;
  // `parseShownQtyInput` both validates that AND rounds it to a whole BASE integer.
  const perPackBase = parseShownQtyInput(packSizeTrimmed, ingredient)
  if (perPackBase == null || perPackBase <= 0) return null
  // Pack COUNT (how many packs) is always a whole number — you don't buy half a pack.
  const packs = Number(qtyInput.trim())
  if (!Number.isInteger(packs) || packs <= 0) return null
  const qtyBase = packs * perPackBase
  if (!Number.isInteger(qtyBase) || qtyBase <= 0) return null
  return { packs, qtyBase }
}
