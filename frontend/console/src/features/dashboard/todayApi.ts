/**
 * todayApi.ts — the per-outlet reads behind the phone home's "today" (ADR 0082).
 *
 * restaurant-service speaks per outlet (`businessId`), the home speaks for the company, so each
 * read here is a `useQueries` fan-out over the company's outlets, folded by `lib/todayView.ts`.
 * Every entry is keyed EXACTLY as the POS hook for the same resource (`features/pos/billsApi.ts
 * useBills`, `features/pos/api.ts useItemSales`), so the home and the till share one cache entry per
 * outlet instead of racing each other. These are POS_ROLES routes (owner/manager pass through the
 * outlet guard), so they use the default outlet bearer like the POS hooks do — a books-only login
 * never reaches this module (DashboardPhone falls back to the monthly composition for it).
 */
import { useQueries } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import type { CompanySession } from '@/lib/session'
import type { BillSummaryResponse } from '@/features/pos/billsApi'
import type { ItemSalesResponse } from '@/features/pos/api'
import type { DailySalesRow } from './lib/todayView'

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

/** One fan-out's roll-up: per-outlet results in `outletIds` order, plus the aggregate state. */
export interface FanOut<T> {
  /** Null while that outlet's call is pending or failed. */
  byOutlet: (T | null)[]
  /** True while ANY outlet is still on its first load. */
  isLoading: boolean
  /** How many outlets failed — the figure is partial until they are retried. */
  failedCount: number
  /** Re-issues only the failed outlets' calls (ADR 0080's per-point retry idiom). */
  retryFailed: () => void
}

interface QueryLike<T> {
  data?: T | null
  isPending: boolean
  isError: boolean
  refetch: () => unknown
}

function rollUp<T>(results: readonly QueryLike<T>[], enabled: boolean): FanOut<T> {
  return {
    byOutlet: results.map((r) => (r.isError ? null : (r.data ?? null))),
    isLoading: enabled && results.some((r) => r.isPending && !r.isError),
    failedCount: results.filter((r) => r.isError).length,
    retryFailed: () => {
      for (const r of results) if (r.isError) void r.refetch()
    },
  }
}

/**
 * GET /api/v1/sales/daily?businessId&from&to per outlet — one row per outlet-local day (inclusive
 * `from`..`to`, YYYY-MM-DD) that had a tendered sale or a refund. 30 s stale: the hero is a live
 * figure, but not a ticker.
 */
export function useDailySalesByOutlet(
  session: CompanySession,
  outletIds: readonly string[],
  window: { from: string; to: string },
  enabled = true,
): FanOut<DailySalesRow[]> {
  return useQueries({
    queries: outletIds.map((businessId) => ({
      enabled,
      queryKey: ['sales-daily', session.companyId, businessId, window.from, window.to],
      staleTime: 30_000,
      queryFn: () =>
        apiFetch<DailySalesRow[]>('/api/v1/sales/daily', {
          tenant: tenantOf(session),
          query: { businessId, from: window.from, to: window.to },
        }),
    })),
    combine: (results) => rollUp(results, enabled),
  })
}

/** GET /api/v1/bills?businessId&status=OPEN per outlet — keyed as `useBills` so the till shares it. */
export function useOpenBillsByOutlet(
  session: CompanySession,
  outletIds: readonly string[],
  enabled = true,
): FanOut<BillSummaryResponse[]> {
  return useQueries({
    queries: outletIds.map((businessId) => ({
      enabled,
      queryKey: ['bills', session.companyId, businessId],
      staleTime: 10_000,
      queryFn: () =>
        apiFetch<BillSummaryResponse[]>('/api/v1/bills', {
          tenant: tenantOf(session),
          query: { businessId, status: 'OPEN' },
        }),
    })),
    combine: (results) => rollUp(results, enabled),
  })
}

/**
 * GET /api/v1/orders/item-sales?businessId&from&to per outlet over one day's instants — keyed as
 * `useItemSales` (same `from`/`to` strings) so the register's daily summary shares it.
 */
export function useItemSalesByOutlet(
  session: CompanySession,
  outletIds: readonly string[],
  bounds: { from: string; to: string },
  enabled = true,
): FanOut<ItemSalesResponse[]> {
  return useQueries({
    queries: outletIds.map((businessId) => ({
      enabled,
      queryKey: ['itemSales', session.companyId, businessId, bounds.from, bounds.to],
      staleTime: 60_000,
      queryFn: () =>
        apiFetch<ItemSalesResponse[]>('/api/v1/orders/item-sales', {
          tenant: tenantOf(session),
          query: { businessId, from: bounds.from, to: bounds.to },
        }),
    })),
    combine: (results) => rollUp(results, enabled),
  })
}
