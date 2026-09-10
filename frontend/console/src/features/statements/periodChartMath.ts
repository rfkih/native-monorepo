/**
 * Pure geometry for the phone reports' period chart (Native Laporan) — no React, no fetch, no
 * formatting, so the scale, the column placement and the colour rule are unit-testable on their
 * own (the house idiom, cf. `balanceSheetView.ts`). Amounts stay integer minor units (rule 8).
 *
 * The chart is not decoration: its twelve columns ARE the period control. Tap a month and the whole
 * report moves to it. That is why the maths lives here — a column that lands on the wrong side of
 * the baseline, or a scale that hides a loss month, is a wrong control, not just a wrong picture.
 *
 * One series per tab, one axis, one baseline (dataviz: never two y-scales). Signed series (net
 * profit, net cash change) cross zero, so the baseline sits where the data puts it; magnitude
 * series (net worth, total expense) sit on the floor.
 */
import { shiftPeriod } from '@/lib/period'

/** Which figure a tab plots — one per tab, never two on one axis. */
export type TrendKey = 'net' | 'netWorth' | 'cash' | 'expense'

/** The colour a column wears. Status tones carry MEANING (profit/loss), ink carries magnitude. */
export type Tone = 'profit' | 'loss' | 'ink'

/** A gap: a month the statement endpoint answered 204 for (no ledger entries yet). */
export type Point = number | null

/** The `months` periods ending at (and including) `period`, oldest first — `usePnlTrend`'s window. */
export function trailingPeriods(period: string, months: number): string[] {
  if (months <= 0) return []
  return Array.from({ length: months }, (_, i) => shiftPeriod(period, -(months - 1 - i)))
}

/**
 * The vertical scale for one series. `upperH` is where the zero baseline sits, measured from the
 * top of a plot `height` tall: a series that never goes negative puts it on the floor, one that never
 * goes positive puts it on the ceiling, and a mixed series splits the height by the larger excursion
 * on each side. Gaps (null) do not take part. An all-zero (or all-gap) series — every new company's
 * first Income tab, since `/api/v1/pnl` answers an empty month with zeros — keeps the baseline on the
 * floor and gets a unit range so nothing divides by zero.
 */
export interface ChartScale {
  height: number
  upperH: number
  range: number
  /** Pixel y (from the top) of a value — the line chart's coordinate. */
  y(v: number): number
}

export function chartScale(values: readonly Point[], height: number): ChartScale {
  const nums = values.filter((v): v is number => v != null)
  const maxPos = Math.max(0, ...nums)
  const maxNeg = Math.max(0, ...nums.map((v) => -v))
  const range = maxPos + maxNeg || 1
  const upperH = maxPos + maxNeg === 0 ? height : (maxPos / range) * height
  return { height, upperH, range, y: (v) => upperH - (v / range) * height }
}

/**
 * One column's box. A positive value grows UP from the baseline (anchored at the bottom, offset by
 * the space below the baseline); a negative one hangs DOWN from it (anchored at the top, offset by
 * the space above). `minH` keeps a near-zero month visible as a sliver rather than vanishing —
 * a month you cannot see is a month you cannot tap.
 */
export interface ColumnGeometry {
  h: number
  anchor: 'top' | 'bottom'
  offset: number
}

export function columnGeometry(v: number, scale: ChartScale, minH = 3): ColumnGeometry {
  const h = Math.max(minH, (Math.abs(v) / scale.range) * scale.height)
  return v < 0
    ? { h, anchor: 'top', offset: scale.upperH }
    : { h, anchor: 'bottom', offset: scale.height - scale.upperH }
}

/**
 * Net profit and net cash change are SIGNED: the colour says which side of zero the month landed
 * on (green means profit and nothing else — ADR 0077). Net worth and total expense are magnitudes:
 * a bigger bar is not better or worse, so they wear ink.
 */
export function seriesTone(key: TrendKey, v: number): Tone {
  if (key === 'net' || key === 'cash') return v < 0 ? 'loss' : 'profit'
  return 'ink'
}

/**
 * The track the columns scroll on. Twelve 44px slots overflow a 412px phone on purpose — the plot
 * scrolls, and the selected column is centred by the component. Below the minimum width the slots
 * stretch to fill so six months are not huddled at the left edge.
 */
export interface ChartTrack {
  /** Full scrollable width — the plot plus an inset at both ends. */
  trackW: number
  colW: number
  gap: number
  /** Breathing room before the first and after the last column; part of the track, so the scroll
   *  clamp below is the browser's own maximum and the last column never pins half off-screen. */
  inset: number
}

export function chartTrack(
  count: number,
  opts: { minW?: number; slot?: number; gap?: number; inset?: number } = {},
): ChartTrack {
  const { minW = 380, slot = 44, gap = 6, inset = 16 } = opts
  const n = Math.max(1, count)
  const plotW = Math.max(minW, n * slot)
  return { trackW: plotW + inset * 2, colW: (plotW - gap * (n - 1)) / n, gap, inset }
}

/** X of a column's centre on the track — the line chart's coordinate. */
export function columnCentre(index: number, track: ChartTrack): number {
  return track.inset + index * (track.colW + track.gap) + track.colW / 2
}

/**
 * The scrollLeft that centres column `index` in a viewport `viewportW` wide, clamped to what the
 * track can actually scroll. Pure so the "never fights a manual scroll" guard around it in the
 * component stays a one-liner.
 */
export function centreScroll(index: number, track: ChartTrack, viewportW: number): number {
  const target = columnCentre(index, track) - viewportW / 2
  return Math.max(0, Math.min(target, Math.max(0, track.trackW - viewportW)))
}

/** The gaps (204 months) and values of a window, as the chart wants them — a plain array. */
export function seriesValues<T>(points: readonly { data: T | null }[], pick: (d: T) => number): Point[] {
  return points.map((p) => (p.data == null ? null : pick(p.data)))
}
