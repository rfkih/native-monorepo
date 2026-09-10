/**
 * stocktakeLines.ts — reading the opname history from an ingredient's side.
 *
 * The server hands back whole stocktakes (newest first, capped at 50), each with every line. The
 * ingredient screen wants the opposite cut: the last few times THIS ingredient was counted, with
 * what the system expected and what was found. Pure, so the ordering rule is testable and the
 * screen only formats.
 */
import type {
  IngredientStocktakeLineResponse,
  IngredientStocktakeResponse,
} from '../ingredientStocktakeApi'
import type { UnitBearing } from './units'

/** How many lines of a stocktake disagreed with the system. */
export function variedCount(stocktake: Pick<IngredientStocktakeResponse, 'lines'>): number {
  return stocktake.lines.filter((line) => line.varianceQty !== 0).length
}

export interface IngredientCount {
  stocktakeId: string
  countedAt: string
  /** null when no costed line was counted — nothing was posted. */
  currency: string | null
  line: IngredientStocktakeLineResponse
}

/**
 * The `n` most recent counts of one ingredient, newest first. The list is re-sorted by `countedAt`
 * rather than trusted: the cap is the server's, the order is this function's.
 */
export function lastCountsFor(
  history: readonly IngredientStocktakeResponse[],
  ingredientId: string,
  n = 3,
): IngredientCount[] {
  const counts: IngredientCount[] = []
  for (const stocktake of history) {
    const line = stocktake.lines.find((l) => l.ingredientId === ingredientId)
    if (line) {
      counts.push({
        stocktakeId: stocktake.id,
        countedAt: stocktake.countedAt,
        currency: stocktake.currency,
        line,
      })
    }
  }
  counts.sort((a, b) => Date.parse(b.countedAt) - Date.parse(a.countedAt))
  return counts.slice(0, n)
}

/**
 * The bearing a line is formatted through: the catalog's (so 8 400 g reads 8,4 kg) ONLY while the
 * line's base unit is still the ingredient's — `convert-unit` rescales recipes and the daily ledger
 * but leaves past stocktake lines in the unit they were counted in, so a `3 pack` line must keep
 * reading as packs after the ingredient moved to grams. A removed ingredient falls back the same way.
 */
export function lineBearing(
  line: Pick<IngredientStocktakeLineResponse, 'ingredientId' | 'unit'>,
  byId: ReadonlyMap<string, UnitBearing>,
): UnitBearing {
  const current = byId.get(line.ingredientId)
  return current && current.unit === line.unit ? current : { unit: line.unit, displayUnit: null }
}
