/**
 * units.ts — display-vs-base unit conversion for ingredient inventory.
 *
 * An ingredient stores its stock (and costs it) in a BASE unit — grams, millilitres, or a countable
 * unit — as a whole INTEGER (the server keeps quantities integer; ArchUnit HR-8 forbids fractional
 * entity fields). To let an owner work in kg/litres with a decimal ("beli 1,5 kg tepung"), the
 * console SHOWS a DISPLAY unit that sits 1000× above the base — kg over g, liter over ml — and
 * converts at the edges: input × 1000 → base integer; base integer ÷ 1000 → display value.
 *
 * The per-display-unit COST is derived from the EXACT total value (`stockValueMinor`), never by
 * scaling the server's per-base `unitCostMinor` cache — rounding a per-gram cost and then ×1000
 * would distort a cheap item by up to ~1000×. Money stays integer minor units throughout (rule 8).
 */

/** A display unit the picker offers that sits above a smaller base unit (always factor 1000). */
const DISPLAY_OVER_BASE: Record<string, { base: string; factor: number }> = {
  kg: { base: 'g', factor: 1000 },
  liter: { base: 'ml', factor: 1000 },
}

/** Minimal shape every helper reads — satisfied by `Ingredient` and by any {unit, displayUnit} pair. */
export type UnitBearing = { unit: string; displayUnit?: string | null }

/**
 * Maps a picker CHOICE (what the user sees — g/kg/ml/liter/pcs/pack) to what gets STORED: the base
 * `unit` plus a `displayUnit` label. A choice that already IS a base unit (g/ml/pcs/pack) stores
 * as-is with `displayUnit: null` (no indirection); kg/liter store their base (g/ml) + the label.
 */
export function unitSelectionToStored(choice: string): { unit: string; displayUnit: string | null } {
  const over = DISPLAY_OVER_BASE[choice]
  return over ? { unit: over.base, displayUnit: choice } : { unit: choice, displayUnit: null }
}

/** The picker choice that corresponds to a stored ingredient (so the edit form pre-selects it). */
export function storedToUnitSelection(ing: UnitBearing): string {
  return ing.displayUnit ?? ing.unit
}

/** The unit an ingredient is SHOWN in — the display label when set, else the stored base unit. */
export function shownUnit(ing: UnitBearing): string {
  return ing.displayUnit ?? ing.unit
}

/** Factor between an ingredient's shown unit and its stored base: 1 for a base unit, 1000 for
 *  kg/liter. Reads only `displayUnit`, so it is 1 whenever there is no display indirection. */
export function shownFactor(ing: { displayUnit?: string | null }): number {
  return ing.displayUnit ? (DISPLAY_OVER_BASE[ing.displayUnit]?.factor ?? 1) : 1
}

/** True when the shown unit admits fractions (kg/liter) — drives `step`/parse on inputs. */
export function allowsFraction(ing: { displayUnit?: string | null }): boolean {
  return shownFactor(ing) > 1
}

/** base INTEGER quantity → the value shown in the display unit (may be fractional). */
export function toDisplayQty(baseQty: number, ing: UnitBearing): number {
  return baseQty / shownFactor(ing)
}

/** a value typed in the display unit → base INTEGER quantity (rounded — the base is always whole). */
export function toBaseQty(displayValue: number, ing: UnitBearing): number {
  return Math.round(displayValue * shownFactor(ing))
}

/**
 * Parses a quantity typed in the SHOWN unit into a base INTEGER, or `null` when invalid/negative.
 * A base unit (factor 1) rejects fractions (there is no half a pcs); a display unit (kg/liter)
 * accepts decimals and rounds the ×1000 result to whole base units.
 */
export function parseShownQtyInput(raw: string, ing: UnitBearing): number | null {
  const trimmed = raw.trim()
  if (trimmed === '') return null
  const val = Number(trimmed)
  if (!Number.isFinite(val) || val < 0) return null
  if (!allowsFraction(ing) && !Number.isInteger(val)) return null
  return toBaseQty(val, ing)
}

/**
 * The cost of ONE shown unit in minor units, derived EXACTLY from the total value so a kg cost is
 * `round(stockValueMinor × 1000 / grams)`, never `round(value/grams) × 1000`. Returns `null` for an
 * uncosted ingredient. With no stock on hand (value is 0 by the V36 invariant) it falls back to the
 * server's per-base cache scaled by the factor — a last-known figure, good enough with zero stock.
 */
export function shownUnitCostMinor(ing: {
  stockQty: number
  stockValueMinor: number
  unitCostMinor: number | null
  displayUnit?: string | null
}): number | null {
  if (ing.unitCostMinor == null) return null
  const factor = shownFactor(ing)
  if (ing.stockQty > 0) return Math.round((ing.stockValueMinor * factor) / ing.stockQty)
  return ing.unitCostMinor * factor
}

/**
 * Formats a base INTEGER quantity in the ingredient's shown unit, locale-aware (rule 9). A display
 * unit shows up to 3 fraction digits (1 g = 0.001 kg); a base unit stays whole.
 */
export function formatShownQty(baseQty: number, ing: UnitBearing, locale: string): string {
  const value = toDisplayQty(baseQty, ing)
  const maximumFractionDigits = allowsFraction(ing) ? 3 : 0
  return new Intl.NumberFormat(locale, { maximumFractionDigits }).format(value)
}
