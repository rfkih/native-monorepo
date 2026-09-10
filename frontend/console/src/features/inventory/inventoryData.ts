/**
 * inventoryData.ts — the three reads every `/inventory/*` screen shares, joined once.
 *
 * The catalog (`/ingredients`), today's consumption (`/ingredients/usage?date=today`) and the
 * seven-day movement roll-up (`/ingredients/stock-history`) are three queries with three failure
 * modes; the screens read ONE shape. The catalog is the only one that gates the page — usage and
 * the roll-up degrade to "no rate" (a row shows "—" for its days) rather than blocking the list,
 * because a shelf that cannot be forecast can still be received against.
 */
import { useMemo } from 'react'
import type { CompanySession } from '@/lib/session'
import {
  useIngredientStockSummary,
  useIngredientUsage,
  useIngredients,
  usageDayKey,
  usageWindowKeys,
  type Ingredient,
} from './ingredientApi'
import {
  buildRows,
  catalogTotals,
  USAGE_WINDOW_DAYS,
  usageRates,
  type CatalogRow,
  type CatalogTotals,
} from './lib/catalogView'

export interface InventoryData {
  session: CompanySession
  query: ReturnType<typeof useIngredients>
  ingredients: Ingredient[]
  byId: Map<string, Ingredient>
  /** Every active ingredient as a catalog row — unfiltered, unranked. */
  rows: CatalogRow[]
  rowById: Map<string, CatalogRow>
  totals: CatalogTotals
  /** True once the movement roll-up has answered (with or without rows). */
  ratesLoaded: boolean
  /** Days the outlet moved stock in the window — the rate's denominator. */
  activeDays: number
}

export function useInventoryData(session: CompanySession): InventoryData {
  const query = useIngredients(session)
  const todayKey = usageDayKey()
  const usage = useIngredientUsage(session, todayKey, true)
  const window = useMemo(() => usageWindowKeys(USAGE_WINDOW_DAYS), [])
  const summary = useIngredientStockSummary(session, window)

  const ingredients = useMemo(() => query.data ?? [], [query.data])
  const rates = useMemo(() => usageRates(summary.data ?? []), [summary.data])
  const usedTodayById = useMemo(
    () => new Map((usage.data ?? []).map((u) => [u.ingredientId, u.qtyUsed])),
    [usage.data],
  )
  const rows = useMemo(
    () => buildRows(ingredients, rates.rateById, usedTodayById),
    [ingredients, rates.rateById, usedTodayById],
  )
  const byId = useMemo(() => new Map(ingredients.map((i) => [i.id, i])), [ingredients])
  const rowById = useMemo(() => new Map(rows.map((r) => [r.ingredient.id, r])), [rows])
  const totals = useMemo(
    () => catalogTotals(rows, session.baseCurrency),
    [rows, session.baseCurrency],
  )

  return {
    session,
    query,
    ingredients,
    byId,
    rows,
    rowById,
    totals,
    ratesLoaded: summary.isSuccess,
    activeDays: rates.activeDays,
  }
}
