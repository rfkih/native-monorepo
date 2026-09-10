/**
 * catalogState.ts — the catalog's two kinds of state, each kept where the navigation contract
 * says it belongs (ADR 0075 N5):
 *
 * - the FILTER chip and the SORT change what the list shows, so they live in the URL
 *   (`?filter=`, `?sort=`) and move with `replace` — Back from a detail returns to the same view,
 *   a reload keeps it, and nothing grows the history stack;
 * - the "Prediksi stok" column is a per-device preference (like the theme), so it lives in
 *   localStorage and never in the URL.
 */
import { useCallback, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { InventoryData } from './inventoryData'
import {
  chipCounts,
  filterRows,
  parseCatalogFilter,
  parseCatalogSort,
  rankRows,
  type CatalogFilter,
  type CatalogRow,
  type CatalogSort,
} from './lib/catalogView'

const DAYS_LEFT_KEY = 'native.inventory.daysLeft'

/** Whether the days-left column is shown; remembered per device, on by default. */
export function useDaysLeftPreference(): [boolean, (next: boolean) => void] {
  const [show, setShow] = useState<boolean>(() => {
    try {
      return localStorage.getItem(DAYS_LEFT_KEY) !== 'off'
    } catch {
      return true
    }
  })
  const set = useCallback((next: boolean) => {
    setShow(next)
    try {
      localStorage.setItem(DAYS_LEFT_KEY, next ? 'on' : 'off')
    } catch {
      // A private window or a blocked store — the toggle still works for this visit.
    }
  }, [])
  return [show, set]
}

export interface CatalogOrder {
  filter: CatalogFilter | null
  sort: CatalogSort
  setFilter: (next: CatalogFilter | null) => void
  setSort: (next: CatalogSort) => void
  /** Rows after the search, the chip and the sort — what the list draws. */
  ranked: CatalogRow[]
  counts: Record<CatalogFilter, number>
}

export function useCatalogOrder(data: InventoryData, locale: string, search: string): CatalogOrder {
  const [params, setParams] = useSearchParams()
  const filter = parseCatalogFilter(params.get('filter'))
  const sort = parseCatalogSort(params.get('sort'))

  const setFilter = useCallback(
    (next: CatalogFilter | null) => {
      const sp = new URLSearchParams(params)
      if (next) sp.set('filter', next)
      else sp.delete('filter')
      setParams(sp, { replace: true })
    },
    [params, setParams],
  )
  const setSort = useCallback(
    (next: CatalogSort) => {
      const sp = new URLSearchParams(params)
      if (next === 'action') sp.delete('sort')
      else sp.set('sort', next)
      setParams(sp, { replace: true })
    },
    [params, setParams],
  )

  const searched = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase(locale)
    if (needle === '') return data.rows
    return data.rows.filter((r) => r.ingredient.name.toLocaleLowerCase(locale).includes(needle))
  }, [data.rows, search, locale])
  const counts = useMemo(() => chipCounts(searched), [searched])
  const ranked = useMemo(
    () => rankRows(filterRows(searched, filter), sort, locale),
    [searched, filter, sort, locale],
  )

  return { filter, sort, setFilter, setSort, ranked, counts }
}
