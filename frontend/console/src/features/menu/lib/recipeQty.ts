/**
 * recipeQty.ts — parsing a recipe line's quantity.
 *
 * A recipe is written in the ingredient's BASE unit, always — never its display unit. A cook says
 * "60 g of meat", not "0.06 kg of meat", and making an owner do that division themselves (at three
 * decimal places, on every line) is not a small friction: in production it made them stop. Every
 * weight-based ingredient — including 1.7 tonnes of kebab meat — ended up in NO recipe at all,
 * which silently emptied both HPP and the ingredient-shortfall detector of the only ingredients
 * worth costing. The display unit stays on STOCK, where "beli 1,5 kg" is how buying is described.
 *
 * Base units are whole by definition: the server stores an INTEGER and 1 g is already the finest
 * grain there is. So a fraction is rejected for every ingredient — no longer a rule that depended
 * on which unit happened to be on screen.
 */

/** The largest quantity a single recipe line may carry — the server column is a 32-bit INTEGER. */
const MAX_BASE_QTY = 2_147_483_647

/**
 * Parses a typed recipe quantity into the signed base INTEGER the server stores, or `null` when it
 * is not a usable quantity.
 *
 * A modifier-option DELTA may be negative ("no cheese" = -20 g), so a sign is accepted here; the
 * caller decides whether a negative value is legal for the line it belongs to (a base line must be
 * positive, a delta must be non-zero).
 */
export function parseRecipeQty(raw: string): number | null {
  const trimmed = raw.trim()
  if (trimmed === '') return null
  const value = Number(trimmed)
  if (!Number.isFinite(value) || !Number.isInteger(value)) return null
  if (Math.abs(value) > MAX_BASE_QTY) return null
  return value
}
