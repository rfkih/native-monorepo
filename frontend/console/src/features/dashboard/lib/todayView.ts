/**
 * todayView.ts — the rules that turn a week of per-outlet day rows into the phone home (ADR 0082).
 *
 * The home leads with TODAY: the company's net sales so far, against the same weekday last week,
 * over a seven-day strip. Every figure here folds the per-outlet rows of `GET /api/v1/sales/daily`
 * (one call per outlet) into company-level days, then reads one number off them. Pure: minor-unit
 * integers and `YYYY-MM-DD` outlet-local day keys in, ratios and orderings out, no formatting (that
 * is the screen's job, through `lib/money.ts` and `Intl`).
 *
 * The day is the OUTLET-LOCAL one — Asia/Jakarta, the same `OutletZone` the server attributes rows
 * to (`ingredientApi.usageDayKey` produces the key). Jakarta has no DST, so day arithmetic is plain
 * 24-hour steps and a day's instants are fixed +07:00 offsets.
 */
import type { CatalogRow } from '@/features/inventory/lib/catalogView'
import { shiftPeriod } from '@/lib/period'

/** Mirror of restaurant-service DailySalesResponse. */
export interface DailySalesRow {
  /** Outlet-local calendar day, YYYY-MM-DD. */
  businessDate: string
  transactionCount: number
  /** Total − refunds attributed to the day, minor units; negative on a refund-only day. */
  netSalesMinor: number
  /** Σ sale-time COGS across the costed sales, or null when none carried one. */
  cogsMinor: number | null
  costedTransactionCount: number
  currency: string
  usesIllustrativeRules: boolean
}

/** One company-level day: every outlet's row for that day folded together. */
export interface DayFigures {
  net: number
  txn: number
  cogs: number | null
  costed: number
  illustrative: boolean
}

/** Mirror of the fields `mergeTopItems` needs from restaurant-service ItemSalesResponse. */
export interface ItemSalesLike {
  menuItemId: string
  name: string
  soldQty: number
  revenueMinor: number
}

export const WEEK_DAYS = 7

const DAY_MS = 86_400_000

const ZERO_DAY: DayFigures = { net: 0, txn: 0, cogs: null, costed: 0, illustrative: false }

/** `key` shifted by `delta` whole days — no zone maths, the key is already an outlet-local day. */
export function dayKeyOffset(key: string, delta: number): string {
  const at = new Date(`${key}T00:00:00Z`)
  return new Date(at.getTime() + delta * DAY_MS).toISOString().slice(0, 10)
}

/** The `days` keys ending at (and including) `todayKey`, oldest first. */
export function weekWindow(todayKey: string, days = WEEK_DAYS): string[] {
  const keys: string[] = []
  for (let i = days - 1; i >= 0; i--) keys.push(dayKeyOffset(todayKey, -i))
  return keys
}

/** The `[from, to)` instants of one Jakarta day, as ISO strings for the window-taking reads. */
export function jakartaDayBounds(key: string): { from: string; to: string } {
  return {
    from: new Date(`${key}T00:00:00+07:00`).toISOString(),
    to: new Date(`${dayKeyOffset(key, 1)}T00:00:00+07:00`).toISOString(),
  }
}

/**
 * Folds every outlet's day rows into company days. An outlet whose call has not resolved (null) is
 * skipped — the figure is provisional while any outlet is loading, and the screen says so. `cogs`
 * stays null until at least one outlet's day carried a fold, so "no costed sale" is distinguishable
 * from "cost zero".
 */
export function mergeDaily(
  perOutlet: readonly (readonly DailySalesRow[] | null | undefined)[],
): Map<string, DayFigures> {
  const series = new Map<string, DayFigures>()
  for (const rows of perOutlet) {
    if (!rows) continue
    for (const r of rows) {
      const cur = series.get(r.businessDate) ?? { ...ZERO_DAY }
      cur.net += r.netSalesMinor
      cur.txn += r.transactionCount
      if (r.cogsMinor != null) cur.cogs = (cur.cogs ?? 0) + r.cogsMinor
      cur.costed += r.costedTransactionCount
      cur.illustrative = cur.illustrative || r.usesIllustrativeRules
      series.set(r.businessDate, cur)
    }
  }
  return series
}

/** A day's figures, zero-filled — the server omits days with no activity. */
export function figuresFor(series: ReadonlyMap<string, DayFigures>, key: string): DayFigures {
  return series.get(key) ?? { ...ZERO_DAY }
}

export interface WeekBar {
  key: string
  net: number
  /** 0–100, relative to the tallest day of the strip; a negative (refund-only) day is 0. */
  pct: number
  isToday: boolean
}

/** The hero strip: one bar per key, scaled to the tallest; the LAST key is today. */
export function weekBars(
  series: ReadonlyMap<string, DayFigures>,
  keys: readonly string[],
): WeekBar[] {
  const nets = keys.map((k) => figuresFor(series, k).net)
  const max = Math.max(0, ...nets)
  return keys.map((key, i) => ({
    key,
    net: nets[i],
    pct: max > 0 && nets[i] > 0 ? (nets[i] / max) * 100 : 0,
    isToday: i === keys.length - 1,
  }))
}

export interface WeekdayDelta {
  /** The compared day — the same weekday one week earlier. */
  lastKey: string
  /** Today − last week, minor units. */
  net: number
  /** Today − last week, transactions. */
  txn: number
  /** Relative change of net; null only when last week's net was not positive (then the whole delta is null). */
  netPct: number
  /** Relative change of the average bill, or null when either day has no transactions. */
  avgPct: number | null
}

/**
 * Today against the same weekday last week — the only comparison that survives a weekend rhythm.
 * Null when last week's day has no positive net: there is nothing honest to compare against, and
 * the screen leaves the line out rather than print an infinite percentage.
 */
export function weekdayDelta(
  series: ReadonlyMap<string, DayFigures>,
  todayKey: string,
): WeekdayDelta | null {
  const lastKey = dayKeyOffset(todayKey, -WEEK_DAYS)
  const today = figuresFor(series, todayKey)
  const last = figuresFor(series, lastKey)
  if (last.net <= 0) return null
  const todayAvg = avgBill(today)
  const lastAvg = avgBill(last)
  return {
    lastKey,
    net: today.net - last.net,
    txn: today.txn - last.txn,
    netPct: (today.net - last.net) / last.net,
    avgPct:
      todayAvg != null && lastAvg != null && lastAvg > 0 ? (todayAvg - lastAvg) / lastAvg : null,
  }
}

/** Net over transactions, or null when nothing was rung. */
export function avgBill(day: DayFigures): number | null {
  return day.txn > 0 ? day.net / day.txn : null
}

/**
 * Gross margin from the sale-time COGS fold: (net − cogs) / net. Null when no sale carried a fold or
 * the net is not positive — a margin of 100% because nothing was costed is a lie, not a figure.
 * `partial` says the fold covers fewer sales than were rung, so the screen marks it.
 */
export function grossMargin(day: DayFigures): { ratio: number; partial: boolean } | null {
  if (day.cogs == null || day.costed === 0 || day.net <= 0) return null
  return { ratio: (day.net - day.cogs) / day.net, partial: day.costed < day.txn }
}

export interface TopItem {
  rank: number
  name: string
  soldQty: number
  revenueMinor: number
}

/**
 * The company's best sellers today. Menu items are per outlet (different ids for the same dish), so
 * rows merge by their sold-time name — trimmed, case-folded — summing quantity and revenue, ranked
 * by quantity then revenue. Outlets still loading (null) are skipped.
 */
export function mergeTopItems(
  lists: readonly (readonly ItemSalesLike[] | null | undefined)[],
  limit = 3,
): TopItem[] {
  const byName = new Map<string, { name: string; soldQty: number; revenueMinor: number }>()
  for (const list of lists) {
    if (!list) continue
    for (const item of list) {
      const name = item.name.trim()
      const k = name.toLocaleLowerCase()
      const cur = byName.get(k) ?? { name, soldQty: 0, revenueMinor: 0 }
      cur.soldQty += item.soldQty
      cur.revenueMinor += item.revenueMinor
      byName.set(k, cur)
    }
  }
  return [...byName.values()]
    .sort((a, b) => b.soldQty - a.soldQty || b.revenueMinor - a.revenueMinor)
    .slice(0, limit)
    .map((t, i) => ({ rank: i + 1, ...t }))
}

export interface OutletShare {
  id: string
  name: string
  net: number
  /** 0–100 relative to the top outlet. */
  pct: number
}

/** Outlets by today's net, busiest first, each bar relative to the busiest. */
export function outletShares(
  rows: readonly { id: string; name: string; net: number }[],
): OutletShare[] {
  const max = Math.max(0, ...rows.map((r) => r.net))
  return rows
    .slice()
    .sort((a, b) => b.net - a.net)
    .map((r) => ({ ...r, pct: max > 0 && r.net > 0 ? (r.net / max) * 100 : 0 }))
}

/**
 * The previous period is "to close" until it appears in the close history — the same rule the
 * PeriodClose screen's open-period banner follows. Returns that period key, or null once closed.
 */
export function periodToClose(
  closedPeriods: readonly string[],
  currentPeriod: string,
): string | null {
  const previous = shiftPeriod(currentPeriod, -1)
  return closedPeriods.includes(previous) ? null : previous
}

/**
 * How many catalog rows are out or nearly out (`zero` + `low`, ADR 0081's ladder), and the first
 * few names — zero first, then fewest days left — for the "stok menipis" line.
 */
export function lowStockNames(
  rows: readonly CatalogRow[],
  limit = 3,
): { count: number; names: string[] } {
  const urgent = rows
    .filter((r) => r.cls === 'zero' || r.cls === 'low')
    .sort(
      (a, b) =>
        (a.cls === 'zero' ? 0 : 1) - (b.cls === 'zero' ? 0 : 1) ||
        (a.days ?? Infinity) - (b.days ?? Infinity),
    )
  return { count: urgent.length, names: urgent.slice(0, limit).map((r) => r.ingredient.name) }
}

/** The avatar monogram: the first letter of the first two words, upper-cased. */
export function initials(name: string): string {
  return name
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0].toLocaleUpperCase())
    .join('')
}
