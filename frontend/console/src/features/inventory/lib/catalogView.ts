/**
 * catalogView.ts — the rules that turn the ingredient catalog into a decision (ADR 0081).
 *
 * A quantity alone never says what to buy: three kilograms is a lot of turmeric and nothing of
 * flour. Divide the stock by what a day consumes and the figure becomes "sisa hari" — days left —
 * which is what the catalog leads with, ranks by, and flags. Everything here is pure: base-unit
 * integers in, classifications and orderings out, no formatting (that is the screen's job, through
 * `units.ts` and `lib/money.ts`).
 *
 * The usage RATE comes from the V47 movement ledger (`/ingredients/stock-history` over the last
 * `USAGE_WINDOW_DAYS`), divided by the number of days the OUTLET moved stock in that window rather
 * than by the calendar: a sporadic ingredient (used once a week) is not read as burning through its
 * stock daily, and a ledger that only started three days ago is not diluted to a seventh of the
 * truth. "Terpakai hari ini" (today's money) stays a separate figure from the single-day endpoint.
 */
import type { Ingredient } from '../ingredientApi'
import { needsUnitConversion } from './unitConversion'

/** The trailing window the usage rate averages over, in outlet-local days (today inclusive). */
export const USAGE_WINDOW_DAYS = 7

/** At or under this many days of stock left an ingredient is "hampir habis". */
export const LOW_STOCK_DAYS = 3

export type CatalogClass = 'zero' | 'low' | 'unit' | 'nocost' | 'ok'
export type CatalogFilter = 'zero' | 'low' | 'unit' | 'nocost'
export type CatalogSort = 'action' | 'value' | 'name'

export const CATALOG_FILTERS: readonly CatalogFilter[] = ['zero', 'low', 'unit', 'nocost']
export const CATALOG_SORTS: readonly CatalogSort[] = ['action', 'value', 'name']

/** What `usageRates` needs from a stock-history summary — the two fields, not the whole record. */
export interface UsageSummaryLike {
  ingredientId: string
  totalUsedQty: number
  daysWithMovement: number
}

/**
 * Per-ingredient usage rate in BASE units per open day. `activeDays` is the most days any single
 * ingredient moved in the window — the closest thing the roll-up offers to "days the outlet was
 * open" — floored at 1 so an empty window cannot divide by zero.
 */
export function usageRates(summaries: readonly UsageSummaryLike[]): {
  activeDays: number
  rateById: Map<string, number>
} {
  const activeDays = Math.max(1, ...summaries.map((s) => s.daysWithMovement))
  const rateById = new Map<string, number>()
  for (const s of summaries) {
    if (s.totalUsedQty > 0) rateById.set(s.ingredientId, s.totalUsedQty / activeDays)
  }
  return { activeDays, rateById }
}

/** Days the stock on hand lasts at the rate, or `null` when nothing is being used. */
export function daysLeft(stockQty: number, rate: number | null | undefined): number | null {
  if (rate == null || rate <= 0) return null
  return stockQty / rate
}

/** The one class a row is drawn in, most urgent first. */
export function classify(ingredient: Ingredient, days: number | null): CatalogClass {
  if (ingredient.stockQty === 0) return 'zero'
  if (days != null && days <= LOW_STOCK_DAYS) return 'low'
  if (needsUnitConversion(ingredient)) return 'unit'
  if (ingredient.unitCostMinor == null) return 'nocost'
  return 'ok'
}

export interface CatalogRow {
  ingredient: Ingredient
  /** Base units per open day, or `null` when the ledger shows no usage in the window. */
  rate: number | null
  /** Today's consumption in base units (0 when the day endpoint omits the ingredient). */
  usedToday: number
  days: number | null
  cls: CatalogClass
}

export function buildRows(
  ingredients: readonly Ingredient[],
  rateById: ReadonlyMap<string, number>,
  usedTodayById: ReadonlyMap<string, number>,
): CatalogRow[] {
  return ingredients.map((ingredient) => {
    const rate = rateById.get(ingredient.id) ?? null
    const days = daysLeft(ingredient.stockQty, rate)
    return {
      ingredient,
      rate,
      usedToday: usedTodayById.get(ingredient.id) ?? 0,
      days,
      cls: classify(ingredient, days),
    }
  })
}

/**
 * The chip predicates are independent of `classify` — a zero-stock `pack` ingredient counts under
 * BOTH "Stok nol" and "Satuan bermasalah", even though its row is drawn in the zero class.
 */
const FILTER_PREDICATE: Record<CatalogFilter, (row: CatalogRow) => boolean> = {
  zero: (row) => row.ingredient.stockQty === 0,
  low: (row) => row.ingredient.stockQty > 0 && row.days != null && row.days <= LOW_STOCK_DAYS,
  unit: (row) => needsUnitConversion(row.ingredient),
  nocost: (row) => row.ingredient.unitCostMinor == null,
}

export function filterRows(
  rows: readonly CatalogRow[],
  filter: CatalogFilter | null,
): CatalogRow[] {
  if (filter == null) return rows.slice()
  return rows.filter(FILTER_PREDICATE[filter])
}

export function chipCounts(rows: readonly CatalogRow[]): Record<CatalogFilter, number> {
  const counts = { zero: 0, low: 0, unit: 0, nocost: 0 }
  for (const filter of CATALOG_FILTERS)
    counts[filter] = rows.filter(FILTER_PREDICATE[filter]).length
  return counts
}

const CLASS_RANK: Record<CatalogClass, number> = { zero: 0, low: 1, unit: 2, nocost: 3, ok: 4 }

/** Stock value, or 0 when an older restaurant-service (pre-ADR 0056) omits the field. */
export function stockValueOf(ingredient: Ingredient): number {
  return Number.isFinite(ingredient.stockValueMinor) ? ingredient.stockValueMinor : 0
}

/**
 * `action`: the class ladder, then fewest days first (no usage last), then name — the row that
 * most needs a decision sits on top. `value`: most money on the shelf first. `name`: A–Z in the
 * locale's collation.
 */
export function rankRows(
  rows: readonly CatalogRow[],
  sort: CatalogSort,
  locale: string,
): CatalogRow[] {
  const byName = (a: CatalogRow, b: CatalogRow) =>
    a.ingredient.name.localeCompare(b.ingredient.name, locale)
  const sorted = rows.slice()
  if (sort === 'name') return sorted.sort(byName)
  if (sort === 'value') {
    return sorted.sort(
      (a, b) => stockValueOf(b.ingredient) - stockValueOf(a.ingredient) || byName(a, b),
    )
  }
  return sorted.sort((a, b) => {
    const rank = CLASS_RANK[a.cls] - CLASS_RANK[b.cls]
    if (rank !== 0) return rank
    if (a.days == null && b.days == null) return byName(a, b)
    if (a.days == null) return 1
    if (b.days == null) return -1
    return a.days - b.days || byName(a, b)
  })
}

export interface CatalogTotals {
  /** Σ stock value over costed ingredients — the figure account 1100 holds once inventory is booked. */
  totalValueMinor: number
  costedCount: number
  uncostedCount: number
  /** Money consumed today (Σ usedToday × unit cost over costed ingredients). */
  usedTodayValueMinor: number
  /** Days the whole shelf lasts at the current burn (total value ÷ Σ rate × cost), or `null`. */
  daysOverall: number | null
}

/**
 * Money totals are summed ONLY over ingredients costed in `currency` (the company base currency —
 * what account 1100 is kept in). An ingredient costed in another currency counts as costed but
 * adds nothing to a total: two currencies never share a sum (rule 8).
 */
export function catalogTotals(rows: readonly CatalogRow[], currency: string): CatalogTotals {
  let totalValueMinor = 0
  let costedCount = 0
  let usedTodayValueMinor = 0
  let burnPerDayMinor = 0
  for (const row of rows) {
    const cost = row.ingredient.unitCostMinor
    if (cost == null) continue
    costedCount += 1
    if (row.ingredient.costCurrency !== currency) continue
    totalValueMinor += stockValueOf(row.ingredient)
    usedTodayValueMinor += row.usedToday * cost
    if (row.rate != null) burnPerDayMinor += row.rate * cost
  }
  return {
    totalValueMinor,
    costedCount,
    uncostedCount: rows.length - costedCount,
    usedTodayValueMinor: Math.round(usedTodayValueMinor),
    daysOverall: burnPerDayMinor > 0 ? totalValueMinor / burnPerDayMinor : null,
  }
}

/** URL → filter; anything unknown reads as "no filter". */
export function parseCatalogFilter(raw: string | null | undefined): CatalogFilter | null {
  return (CATALOG_FILTERS as readonly string[]).includes(raw ?? '') ? (raw as CatalogFilter) : null
}

/** URL → sort; anything unknown reads as the default `action` order. */
export function parseCatalogSort(raw: string | null | undefined): CatalogSort {
  return (CATALOG_SORTS as readonly string[]).includes(raw ?? '') ? (raw as CatalogSort) : 'action'
}

export function nextCatalogSort(sort: CatalogSort): CatalogSort {
  return CATALOG_SORTS[(CATALOG_SORTS.indexOf(sort) + 1) % CATALOG_SORTS.length]
}
