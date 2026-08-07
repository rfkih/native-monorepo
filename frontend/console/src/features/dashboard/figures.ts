/**
 * P&L figure helpers, extracted verbatim from Dashboard.tsx (pure code motion) so the phone
 * dashboard (DashboardPhone) reads periods and figures exactly the way the desktop does.
 */
import type { PnlResponse } from './api'

export interface Figures {
  revenue: number
  expense: number
  net: number
}

export function readFigures(data: PnlResponse | null, converted: boolean): Figures {
  if (!data) return { revenue: 0, expense: 0, net: 0 }
  if (converted) {
    return {
      revenue: data.presentationRevenueMinor ?? 0,
      expense: data.presentationExpenseMinor ?? 0,
      net: data.presentationNetMinor ?? 0,
    }
  }
  return { revenue: data.revenueMinor, expense: data.expenseMinor, net: data.netMinor }
}

/** 'YYYY-MM' → the locale-short month name (e.g. "Jun"). */
export function monthShort(period: string, locale: string): string {
  const [y, m] = period.split('-').map(Number)
  return new Date(y, m - 1, 1).toLocaleDateString(locale, { month: 'short' })
}
