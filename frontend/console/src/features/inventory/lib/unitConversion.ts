/**
 * unitConversion.ts — moving an ingredient off a base unit that is too coarse to cook with.
 *
 * A `pack` is a PURCHASE container, not a unit of consumption. You buy sauce by the pack and use it
 * by the gram, and an ingredient whose base unit is `pack` cannot appear in a recipe at all — a pack
 * has nothing beneath it, so the smallest expressible use is one whole pack. In production that is
 * exactly what happened: the sauce, and every weight ingredient in its own way, ended up in no
 * recipe at all.
 *
 * The picker no longer offers `pack` (a pack is expressed as a fine base unit plus a `packSize`, so
 * buying by the pack still works — see V46), but ingredients created before that are still out
 * there. This module decides which ones need moving and validates the factor that moves them.
 */
import { unitSelectionToStored } from './units'

/** Base units an ingredient can be consumed from — anything else needs converting. */
const CONSUMABLE_BASE_UNITS = new Set(['g', 'ml', 'pcs'])

/** The largest stock quantity the server's INTEGER column can hold. */
const MAX_STOCK_QTY = 2_147_483_647

/**
 * True when this ingredient's base unit cannot express a real recipe quantity.
 *
 * `pcs` is fine — half a bread roll is not a thing, and 1 is a legitimate portion. `pack` is not:
 * nobody puts a whole kilogram of sauce in one kebab, so the only expressible quantity is wrong.
 */
export function needsUnitConversion(ingredient: { unit: string }): boolean {
  return !CONSUMABLE_BASE_UNITS.has(ingredient.unit)
}

/** What a conversion would produce, or why it cannot be performed. */
export type ConversionPreview =
  | { ok: true; toUnit: string; toDisplayUnit: string | null; factor: number; newStockQty: number }
  | { ok: false; reason: 'factor' | 'overflow' }

/**
 * Validates "1 <old unit> = N <new unit>" and previews the result.
 *
 * The factor must be a positive whole number: a conversion needing a fraction would be going the
 * wrong way, toward a COARSER unit, which loses exactly the precision this exists to gain. The
 * overflow check mirrors the server's — refusing here means the owner sees a field error instead of
 * a 422 after committing to the dialog.
 */
export function previewConversion(
  unitChoice: string,
  factorInput: string,
  currentStockQty: number,
): ConversionPreview {
  const trimmed = factorInput.trim()
  const factor = Number(trimmed)
  if (trimmed === '' || !Number.isFinite(factor) || !Number.isInteger(factor) || factor <= 0) {
    return { ok: false, reason: 'factor' }
  }
  const newStockQty = currentStockQty * factor
  if (newStockQty > MAX_STOCK_QTY) {
    return { ok: false, reason: 'overflow' }
  }
  const stored = unitSelectionToStored(unitChoice)
  return {
    ok: true,
    toUnit: stored.unit,
    toDisplayUnit: stored.displayUnit,
    factor,
    newStockQty,
  }
}
