/**
 * ingredientLink.ts — pure parse/validate logic for an AP bill line's OPTIONAL ingredient linkage
 * (ADR 0072 P4), extracted so it is unit-testable without rendering (mirrors
 * `features/expenses/lib/companyExpenseForm.ts`'s `parseInventoryLine` — same shape, this is its
 * AP-bill counterpart: no `valueMinor` here, since the line's own `unitPriceMinor × quantity`
 * already carries the money; the linkage is quantity-only).
 *
 * The linkage is optional even on an inventory-flagged line (a bare `inventory: true` with no
 * linkage stays fully supported — it only changes GL routing, ADR 0067). Quantity parsing reuses
 * `features/inventory/lib/units.ts`'s display-unit→base-unit conversion — entered in the
 * ingredient's DISPLAY unit (kg/liter), converted to the BASE integer the server stores.
 */
import { parseShownQtyInput, type UnitBearing } from '@/features/inventory/lib/units'

export interface IngredientLinkDraft {
  ingredientId: string
  ingredientName: string
  /** Quantity typed in the ingredient's DISPLAY unit (kg/liter accept decimals). */
  qtyInput: string
}

export interface ParsedIngredientLink {
  ingredientId: string
  ingredientName: string
  ingredientQtyBase: number
}

export interface IngredientLinkResult {
  /** The parsed trio, or `null` when nothing was entered OR the entry doesn't (yet) resolve. */
  link: ParsedIngredientLink | null
  /**
   * `false` only for a PARTIALLY entered draft (e.g. an ingredient picked with no quantity, or a
   * quantity that fails to convert to a positive BASE integer) — the caller should block the whole
   * line's submit rather than silently drop the half-entered linkage. `true` for both "nothing
   * entered" (`link: null`) and "a complete, valid trio" (`link` set).
   */
  valid: boolean
}

const EMPTY_LINK: IngredientLinkResult = { link: null, valid: true }

/**
 * Parses an OPTIONAL ingredient linkage. Mirrors the server's V59 CHECK constraints exactly:
 * `ingredientId` and a resolvable positive `ingredientQtyBase` go together (both or neither).
 */
export function parseIngredientLink(
  draft: IngredientLinkDraft,
  ingredient: UnitBearing | null,
): IngredientLinkResult {
  const hasIngredient = draft.ingredientId !== ''
  const hasQty = draft.qtyInput.trim() !== ''
  if (!hasIngredient && !hasQty) return EMPTY_LINK
  if (!hasIngredient || !ingredient) return { link: null, valid: false }

  const qtyBase = parseShownQtyInput(draft.qtyInput, ingredient)
  if (qtyBase == null || qtyBase <= 0) return { link: null, valid: false }

  return {
    link: {
      ingredientId: draft.ingredientId,
      ingredientName: draft.ingredientName,
      ingredientQtyBase: qtyBase,
    },
    valid: true,
  }
}
