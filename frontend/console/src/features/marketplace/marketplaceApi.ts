/**
 * Marketplace / Platform Online report — typed client for the two period-scoped summary endpoints
 * this report joins against the existing outstanding-per-channel hook
 * (`features/platform/platformApi.ts`'s `useOutstanding`, reused as-is, not duplicated here):
 *
 *  - restaurant-service: ONLINE sales rung through a channel (GoFood/GrabFood-style — see
 *    `features/channels/`) in the period.
 *  - finance-service: settlements RECORDED against a channel in the period (mirrors
 *    `features/platform/platformApi.ts`'s settlement history, but pre-aggregated per channel).
 *
 * Dashboard-facing (owner/accountant, mirrors `/platform-settlements` — `financeAllowed` in
 * App.tsx) — every call uses the PERSONAL bearer (ADR 0049 P3b), exactly like platformApi.ts.
 *
 * The restaurant-service path is a best-guess pending the backend landing in parallel (see the task
 * brief) — kept as the single exported constant {@link SALES_CHANNEL_SUMMARY_PATH} so a path change
 * is a one-line edit here, not a hunt through the feature.
 *
 * Money rule 8: every amount on the wire is an integer MINOR unit + an ISO-4217 currency, never a
 * float.
 */
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { apiFetch as apiFetchBase, type RequestOptions } from '@/lib/api'
import type { CompanySession } from '@/lib/session'

/** Shadows the shared `apiFetch` so every call in this module is PERSONAL-bearer by default with
 *  zero per-call changes — see this file's header doc (ADR 0049 P3b). */
function apiFetch<T>(path: string, opts: RequestOptions = {}) {
  return apiFetchBase<T>(path, { ...opts, auth: 'personal' })
}

/** restaurant-service — ONLINE sales per channel for a period. Isolated here so a backend path
 *  change (the task brief flags the exact path as provisional) is a one-line edit. */
const SALES_CHANNEL_SUMMARY_PATH = '/api/v1/sales/channel-summary'

/** finance-service — settlements recorded per channel for a period. */
const PLATFORM_SETTLEMENT_SUMMARY_PATH = '/api/v1/platform-settlements/summary'

/** Mirrors restaurant-service's per-channel ONLINE sales summary row exactly. */
export interface ChannelSalesSummary {
  channelCode: string
  grossSalesMinor: number
  transactionCount: number
  currency: string
}

/** Mirrors finance-service's per-channel settlement summary row exactly. */
export interface ChannelSettlementSummary {
  channelCode: string
  settledGrossMinor: number
  feeMinor: number
  netMinor: number
  settlementCount: number
  currency: string
}

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

/**
 * GET /api/v1/sales/channel-summary?period=YYYY-MM — ONLINE sales per channel in the
 * period. `keepPreviousData` (mirrors `features/statements/api.ts`) keeps the prior month's rows on
 * screen while stepping — no flash-to-empty on every PeriodNav click.
 */
export function useChannelSalesSummary(session: CompanySession, period: string) {
  return useQuery({
    queryKey: ['marketplace-sales-summary', session.companyId, period],
    placeholderData: keepPreviousData,
    queryFn: async () => {
      const result = await apiFetch<ChannelSalesSummary[]>(SALES_CHANNEL_SUMMARY_PATH, {
        tenant: tenantOf(session),
        query: { period },
      })
      return result ?? []
    },
  })
}

/** GET /api/v1/platform-settlements/summary?period=YYYY-MM — settlements recorded per channel in
 *  the period. */
export function useChannelSettlementSummary(session: CompanySession, period: string) {
  return useQuery({
    queryKey: ['marketplace-settlement-summary', session.companyId, period],
    placeholderData: keepPreviousData,
    queryFn: async () => {
      const result = await apiFetch<ChannelSettlementSummary[]>(PLATFORM_SETTLEMENT_SUMMARY_PATH, {
        tenant: tenantOf(session),
        query: { period },
      })
      return result ?? []
    },
  })
}
