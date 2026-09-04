/**
 * ingredientLink.ts — pure parse/validate logic for a Persediaan-ticked AP bill line (ADR 0072 P4;
 * reworked 2026-09-04 per owner UX correction: the DESCRIPTION FIELD ITSELF is now the ingredient
 * combobox — a ticked line is either fully linked or not submittable, there is no more
 * "inventory-flagged but unlinked" shape in the UI). Extracted so it is unit-testable without
 * rendering (mirrors `features/expenses/lib/companyExpenseForm.ts`'s `parseInventoryLine` — the
 * two purchase forms now share the same bahan + jumlah + total-dibayar shape).
 *
 * Quantity parsing reuses `features/inventory/lib/units.ts`'s display-unit→base-unit conversion
 * (entered in the ingredient's DISPLAY unit, e.g. "2.5 kg"); money parsing reuses
 * `features/pos/lib/discountInput.ts`'s major-input→minor-units convention. The backend's
 * `quantity` is an INTEGER and cannot carry a fractional real quantity, so the wire line always
 * sends `quantity: 1` with the entered TOTAL as `unitPriceMinor` (1 × total reproduces the total
 * exactly) — the REAL quantity rides `ingredientQtyBase` instead.
 */
import { parseShownQtyInput, type UnitBearing } from '@/features/inventory/lib/units'
import { parseDiscountInput } from '@/features/pos/lib/discountInput'

export interface InventoryLineDraft {
  ingredientId: string
  ingredientName: string
  /** Quantity typed in the ingredient's DISPLAY unit (kg/liter accept decimals). */
  qtyInput: string
  /** TOTAL price for the WHOLE line, MAJOR units (not per-unit). */
  totalInput: string
}

export interface ParsedInventoryLine {
  /** Sent as the line's `description` — the ingredient's own name (the combobox IS the
   *  description field once linked, so there is nothing else to send). */
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
 * Parses a Persediaan-ticked line, or `null` when not (yet) submittable: an ingredient MUST be
 * resolved (picked from the combobox or freshly created — never a bare free-text description), the
 * quantity must convert to a strictly positive BASE integer via the ingredient's own display-unit
 * factor, and the total must be strictly positive.
 */
export function parseInventoryLine(
  draft: InventoryLineDraft,
  ingredient: UnitBearing | null,
  currency: string,
): ParsedInventoryLine | null {
  if (!draft.ingredientId || !ingredient) return null
  const qtyBase = parseShownQtyInput(draft.qtyInput, ingredient)
  if (qtyBase == null || qtyBase <= 0) return null
  const totalMinor = parseDiscountInput(draft.totalInput, currency)
  if (totalMinor <= 0) return null
  return {
    description: draft.ingredientName,
    quantity: 1,
    unitPriceMinor: totalMinor,
    ingredientId: draft.ingredientId,
    ingredientName: draft.ingredientName,
    ingredientQtyBase: qtyBase,
  }
}
