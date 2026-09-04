/**
 * ingredientLink.ts — pure parse/validate logic for a Persediaan-ticked AP bill line (ADR 0072 P4;
 * reworked 2026-09-04 per owner UX correction: the DESCRIPTION FIELD ITSELF is the ingredient
 * combobox by default — a ticked line is either fully linked or not submittable, there is no more
 * "inventory-flagged but unlinked" shape in the UI). Extracted so it is unit-testable without
 * rendering (mirrors `features/expenses/lib/companyExpenseForm.ts`'s `parseInventoryLine` — the
 * two purchase forms share the same bahan + jumlah + total-dibayar shape).
 *
 * Reworked AGAIN 2026-09-04 (same day, "receipt name differs" follow-up) — `description` is now an
 * INDEPENDENT field on the draft rather than always mirroring `ingredientName`: a supplier's
 * invoice often writes its own product name (e.g. "AYAM BROILER FROZEN 1KG") that doesn't match
 * the inventory item name ("Ayam fillet"). NewBill.tsx's "Nama di nota berbeda" toggle keeps
 * `description` in sync with `ingredientName` by default (the common case — combobox IS the
 * description) but lets the operator diverge it to match the physical receipt while the ingredient
 * link (and its `ingredientQtyBase`) rides independently, so stock still lands on the right item.
 *
 * Quantity parsing reuses `features/inventory/lib/units.ts`'s display-unit→base-unit conversion
 * (entered in the ingredient's DISPLAY unit, e.g. "2.5 kg"); money parsing reuses
 * `features/pos/lib/discountInput.ts`'s major-input→minor-units convention. The backend's
 * `quantity` is an INTEGER and cannot carry a fractional real quantity, so the wire line always
 * sends `quantity: 1` with the entered TOTAL as `unitPriceMinor` (1 × total reproduces the total
 * exactly) — the REAL quantity rides `ingredientQtyBase` instead.
 *
 * Pack-size maths (`parsePackedQtyBase`/`PackedQty`) lives in `features/inventory/lib/packQty.ts`
 * (code-review F1) — re-exported here so this module's own call sites (`NewBill.tsx`) are unchanged.
 */
import type { UnitBearing } from '@/features/inventory/lib/units'
import { parsePackedQtyBase, type PackedQty } from '@/features/inventory/lib/packQty'
import { parseDiscountInput } from '@/features/pos/lib/discountInput'

export { parsePackedQtyBase, type PackedQty }

export interface InventoryLineDraft {
  /** What gets sent as the line's `description` — independent of `ingredientName` (see this
   *  module's doc); required non-blank even when it equals the ingredient's name (the default,
   *  common case — NewBill.tsx keeps them in sync unless "Nama di nota berbeda" is ticked). */
  description: string
  ingredientId: string
  ingredientName: string
  /** Quantity typed in the ingredient's DISPLAY unit (kg/liter accept decimals) — or, once
   *  `packSizeInput` is set, the NUMBER OF PACKS instead (see its doc). */
  qtyInput: string
  /** TOTAL price for the WHOLE line, MAJOR units (not per-unit). This is what the receipt says
   *  the line cost — unaffected by pack maths, which only feeds `ingredientQtyBase`. */
  totalInput: string
  /**
   * Owner request — a vendor sells by the PACK while inventory counts CONTENTS (e.g. a receipt
   * says "TORTILLA 1 PCS" for a pack of 20 individual tortillas). Optional; BLANK (default) is
   * today's behaviour unchanged — `qtyInput` is a plain display-unit quantity. NON-BLANK means
   * `qtyInput` now counts PACKS (always a whole number — you don't buy half a pack), and this is
   * how many of the ingredient's DISPLAY unit are in ONE pack — SAME fraction rule as any other
   * quantity (decimal allowed for kg/liter, e.g. "2.5" kg/pack; whole for pcs/pack), converted to a
   * whole BASE integer exactly like `qtyInput` itself. Deliberately separate from
   * `features/inventory/lib/units.ts`'s `shownFactor`/`DISPLAY_OVER_BASE` (a fixed 1000× kg/g,
   * liter/ml family) — a pack size is an arbitrary per-product number; see `parsePackedQtyBase`.
   */
  packSizeInput: string
}

export interface ParsedInventoryLine {
  description: string
  /** Always 1 — see this module's doc for why. */
  quantity: 1
  /** = the entered TOTAL in minor units (quantity(1) × unitPriceMinor reproduces it exactly). */
  unitPriceMinor: number
  ingredientId: string
  ingredientName: string
  ingredientQtyBase: number
}

/**
 * Parses a Persediaan-ticked line, or `null` when not (yet) submittable: a non-blank `description`
 * (the receipt wording — independent of the linked ingredient's own name), an ingredient MUST be
 * resolved (picked from the combobox or freshly created — never a bare free-text description with
 * no link), the quantity (optionally pack-scaled — see `parsePackedQtyBase`) must convert to a
 * strictly positive BASE integer, and the total must be strictly positive.
 */
export function parseInventoryLine(
  draft: InventoryLineDraft,
  ingredient: UnitBearing | null,
  currency: string,
): ParsedInventoryLine | null {
  const description = draft.description.trim()
  if (!description) return null
  if (!draft.ingredientId || !ingredient) return null
  const packed = parsePackedQtyBase(draft.qtyInput, draft.packSizeInput, ingredient)
  if (!packed) return null
  const totalMinor = parseDiscountInput(draft.totalInput, currency)
  if (totalMinor <= 0) return null
  return {
    description,
    quantity: 1,
    unitPriceMinor: totalMinor,
    ingredientId: draft.ingredientId,
    ingredientName: draft.ingredientName,
    ingredientQtyBase: packed.qtyBase,
  }
}
