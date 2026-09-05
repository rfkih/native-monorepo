/**
 * Sales-leak report — typed client for restaurant-service's owner-only detection endpoint
 * (ADR 0074), `GET /api/v1/sales-integrity/report`.
 *
 * The server returns MACHINE signals and integer minor units only: no title, no explanation, no
 * advice (rule 9). Every word the owner reads is rendered here from the `salesIntegrity.*` i18n
 * block, in both locales, keyed off the signal type. Adding a signal server-side therefore means
 * adding copy in en.ts AND id.ts — a type with no copy renders as its bare enum name, which is the
 * intended loud failure rather than a silent English leak into an Indonesian UI.
 *
 * Money rule 8: every amount on the wire is an integer MINOR unit plus an ISO-4217 code, formatted
 * through `formatMoney` (Intl), never string-concatenated.
 */
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { apiFetch as apiFetchBase, type RequestOptions } from '@/lib/api'
import type { CompanySession } from '@/lib/session'

/** Owner-gated dashboard surface, so the PERSONAL bearer (ADR 0049 P3b), never a device token. */
function apiFetch<T>(path: string, opts: RequestOptions = {}) {
  return apiFetchBase<T>(path, { ...opts, auth: 'personal' })
}

const REPORT_PATH = '/api/v1/sales-integrity/report'

/**
 * The signal kinds the backend can raise — mirrors `LeakSignalType` exactly. Kept as a union rather
 * than a string so a rename on either side is a compile error here instead of a blank card.
 */
export type LeakSignalType =
  | 'MISSING_TRACKED_ITEMS'
  | 'INGREDIENT_SHORTFALL'
  | 'DARK_HOUR'
  | 'SALES_OUTSIDE_SESSION'
  | 'TRADING_DAY_WITHOUT_CLOSE'
  | 'PERSISTENT_CASH_SHORT'
  | 'UNEXPLAINED_CASH_OVER'
  | 'SESSION_LEFT_OPEN'
  | 'EXACT_ZERO_CLOSE_RUN'

export type LeakSeverity = 'HIGH' | 'MEDIUM' | 'LOW'

/** One piece of evidence under a signal. Fields a given signal cannot fill arrive as null. */
export interface LeakDetail {
  subjectId: string | null
  /** An ingredient / menu-item name or a login id — DATA, never UI copy. */
  subjectName: string | null
  businessDate: string | null
  hourOfDay: number | null
  quantity: number | null
  valueMinor: number | null
  currency: string | null
}

export interface LeakSignal {
  type: LeakSignalType
  severity: LeakSeverity
  occurrences: number
  estimatedValueMinor: number | null
  currency: string | null
  details: LeakDetail[]
}

/** What the report could NOT see. Rendered next to the estimate, never tucked away below it. */
export interface LeakCoverage {
  totalSoldQty: number
  recipeBackedSoldQty: number
  /** null = never counted, which is a different statement from "counted 0 days ago". */
  daysSinceIngredientCount: number | null
  daysSinceItemCount: number | null
  manualStockCorrections: number
}

export interface SalesIntegrityReport {
  businessId: string
  from: string
  to: string
  currency: string | null
  estimatedLeakMinorLow: number
  estimatedLeakMinorHigh: number
  confirmedMissingCostMinor: number
  signals: LeakSignal[]
  coverage: LeakCoverage
}

/**
 * The outlet-local bounds of a `YYYY-MM` period, as instants.
 *
 * <p>Asia/Jakarta, fixed — the backend buckets every business date in that zone (it has no DST, so
 * the offset is a constant +07:00). Sending the BROWSER's month bounds instead would shift the
 * window by the viewer's own offset, so the same report would cover different days depending on
 * where it was opened, and the last evening of the month would fall outside its own report.
 */
export function jakartaMonthBounds(period: string): { from: string; to: string } {
  const [year, month] = period.split('-').map(Number)
  // Jakarta midnight on the 1st is 17:00 UTC on the last day of the previous month.
  const from = new Date(Date.UTC(year, month - 1, 1, -7, 0, 0))
  const to = new Date(Date.UTC(year, month, 1, -7, 0, 0))
  return { from: from.toISOString(), to: to.toISOString() }
}

/**
 * `GET /api/v1/sales-integrity/report` for one outlet and month.
 *
 * `keepPreviousData` (the statements/marketplace precedent) keeps the previous month's findings on
 * screen while stepping, so the page does not flash to a skeleton — and, more importantly here, so
 * a momentarily empty state never reads as "this month is clean".
 */
export function useSalesIntegrityReport(
  session: CompanySession,
  businessId: string,
  period: string,
) {
  const { from, to } = jakartaMonthBounds(period)
  return useQuery({
    queryKey: ['sales-integrity', session.companyId, businessId, period],
    placeholderData: keepPreviousData,
    queryFn: async () => {
      const report = await apiFetch<SalesIntegrityReport>(REPORT_PATH, {
        tenant: { companyId: session.companyId, actor: session.actor },
        query: { businessId, from, to },
      })
      return report ?? null
    },
  })
}

/** Severity ordering for display: the ones worth looking at first, first. */
export const SEVERITY_ORDER: Record<LeakSeverity, number> = { HIGH: 0, MEDIUM: 1, LOW: 2 }
