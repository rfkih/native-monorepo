/**
 * PeriodChart — the phone reports' twelve-month chart, which is also the period control (Native
 * Laporan). Tap a column and the report moves to that month; the selected column is the only one in
 * colour, so the chart says "you are here" as well as "this is the shape of the year".
 *
 * Presentation only: the maths is `periodChartMath.ts`, the data comes in as a plain series. One
 * scroller carries BOTH the plot and its month labels, so a column can never drift away from its
 * own label, and the track carries its own end inset so the scroll clamp is the browser's real
 * maximum; the selected column is centred whenever the selection or the window changes, and left
 * alone otherwise so it never fights a manual scroll (the pin key).
 *
 * dataviz specs kept: columns ≤24px thick in a 44px slot (the rest is air), 4px rounded data-end and
 * square at the baseline, 2px line with round joins, ≥8px selected marker with a 2px surface ring,
 * a solid 1px baseline one step off the surface, the area as a quiet wash. Labels wear text tokens;
 * only the marks wear the series colour. Each column is a ≥24px hit target with a title tooltip —
 * the tooltip enhances, never gates: the selected value is the hero above, every value is in the
 * ledger below.
 *
 * Two kinds of nothing: an EMPTY month (204) is a gap with "no data" in its tooltip; a FAILED month
 * (the request errored) is a gap wearing a warning mark on the baseline — status colour with an icon
 * and a label, never colour alone — and tapping it retries that month as well as selecting it.
 */
import { useEffect, useMemo, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { TriangleAlert } from 'lucide-react'
import { cn } from '@/lib/cn'
import {
  centreScroll,
  chartScale,
  chartTrack,
  columnCentre,
  columnGeometry,
  type Point,
  type Tone,
} from './periodChartMath'

const TONE_FILL: Record<Tone, string> = {
  profit: 'bg-profit',
  loss: 'bg-loss',
  ink: 'bg-ink',
}
const TONE_SVG: Record<Tone, string> = {
  profit: 'var(--color-profit)',
  loss: 'var(--color-loss)',
  ink: 'var(--color-ink)',
}

export function PeriodChart({
  periods,
  values,
  failed,
  selected,
  onSelect,
  onRetry,
  type,
  tone,
  formatValue,
  locale,
  height = 132,
}: {
  /** `YYYY-MM`, oldest first. */
  periods: string[]
  /** One per period; null draws as a gap (a 204 month — or a failed one, see `failed`). */
  values: Point[]
  /** One per period; a failed month wears a warning mark instead of reading as empty. */
  failed?: boolean[]
  selected: string
  onSelect: (period: string) => void
  /** Called (before `onSelect`) when a failed column is tapped, so the caller can re-issue it. */
  onRetry?: (period: string) => void
  type: 'bar' | 'line'
  tone: (v: number) => Tone
  /** For the column tooltip — already locale-aware (formatMoney). */
  formatValue: (v: number) => string
  locale: string
  height?: number
}) {
  const { t } = useTranslation()
  const scrollerRef = useRef<HTMLDivElement>(null)
  const pinRef = useRef('')

  const track = useMemo(() => chartTrack(periods.length), [periods.length])
  const scale = chartScale(values, height)
  const monthOf = new Intl.DateTimeFormat(locale, { month: 'short', timeZone: 'UTC' })
  const label = (p: string) => {
    const [y, m] = p.split('-').map(Number)
    return monthOf.format(new Date(Date.UTC(y, m - 1, 1)))
  }
  const selectedIndex = periods.indexOf(selected)

  // Centre the selected column when the selection or the window changes — and only then.
  useEffect(() => {
    const el = scrollerRef.current
    if (!el || selectedIndex < 0) return
    const key = `${selected}:${periods.length}`
    if (pinRef.current === key) return
    pinRef.current = key
    el.scrollLeft = centreScroll(selectedIndex, track, el.clientWidth)
  }, [selected, selectedIndex, periods.length, track])

  const barW = Math.min(24, Math.floor(track.colW) - 4)
  const isLine = type === 'line'
  const present = values
    .map((v, i) => (v == null ? null : { i, v }))
    .filter((p): p is { i: number; v: number } => p != null)
  // A gap (a 204 month) breaks the line: consecutive present months form one run each, and a run
  // is drawn only when it has two ends — a lone month keeps its marker and nothing else.
  const runs = present.reduce<{ i: number; v: number }[][]>((acc, p) => {
    const last = acc[acc.length - 1]
    if (last && last[last.length - 1].i === p.i - 1) last.push(p)
    else acc.push([p])
    return acc
  }, [])
  const pt = (p: { i: number; v: number }) => `${columnCentre(p.i, track)},${scale.y(p.v)}`

  return (
    <div
      ref={scrollerRef}
      className="overflow-x-auto [-ms-overflow-style:none] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
    >
      <div className="flex flex-col gap-2" style={{ width: track.trackW }}>
        {/* Plot */}
        <div className="relative" style={{ height: height + 6 }}>
          {/* Zero baseline — a hairline, one step off the surface, never dashed. */}
          <span
            aria-hidden="true"
            className="absolute h-px bg-line"
            style={{ top: scale.upperH, left: track.inset, right: track.inset }}
          />
          {isLine && present.length > 0 ? (
            <svg
              width={track.trackW}
              height={height}
              viewBox={`0 0 ${track.trackW} ${height}`}
              className="pointer-events-none absolute left-0 top-0"
              aria-hidden="true"
            >
              {runs
                .filter((run) => run.length > 1)
                .map((run) => (
                  <g key={run[0].i}>
                    {/* The area between the line and zero: a wash, quiet enough not to compete. */}
                    <polygon
                      className="reveal"
                      points={[
                        `${columnCentre(run[0].i, track)},${scale.upperH}`,
                        ...run.map(pt),
                        `${columnCentre(run[run.length - 1].i, track)},${scale.upperH}`,
                      ].join(' ')}
                      fill="var(--color-hover)"
                    />
                    <polyline
                      points={run.map(pt).join(' ')}
                      pathLength={100}
                      className="draw-path"
                      fill="none"
                      stroke="var(--color-ink)"
                      strokeWidth={2}
                      strokeLinejoin="round"
                      strokeLinecap="round"
                    />
                  </g>
                ))}
              {present.map((p) => {
                const sel = periods[p.i] === selected
                return (
                  <circle
                    key={p.i}
                    cx={columnCentre(p.i, track)}
                    cy={scale.y(p.v)}
                    r={sel ? 5 : 2.5}
                    fill={sel ? TONE_SVG[tone(p.v)] : 'var(--color-ink-300)'}
                    stroke={sel ? 'var(--color-surface)' : 'none'}
                    strokeWidth={sel ? 2.5 : 0}
                  />
                )
              })}
            </svg>
          ) : null}

          {/* Columns — the period control. Each is a full-height hit target. The row is exactly
              `height` tall (the plot box carries 6px more for the selected marker's ring), so a
              bottom-anchored column ends ON the baseline, not below it. */}
          {/* Keyed on the SERIES, so switching what the chart shows (income ↔ balance ↔ cash, net ↔
              expense) regrows the columns the way the home's strip does on arrival — while tapping
              a column to pick a period keeps the same nodes and moves nothing but the tint. */}
          <div
            key={values.map((v) => v ?? 'x').join('|')}
            className="relative flex"
            style={{ height, gap: track.gap, paddingInline: track.inset }}
          >
            {periods.map((p, i) => {
              const v = values[i]
              const sel = p === selected
              const isFailed = failed?.[i] === true
              const geo = v == null ? null : columnGeometry(v, scale)
              const title = isFailed
                ? t('statements.phone.monthFailed', { month: label(p) })
                : v == null
                  ? `${label(p)} · ${t('statements.phone.noData')}`
                  : `${label(p)} · ${formatValue(v)}`
              return (
                <button
                  key={p}
                  type="button"
                  onClick={() => {
                    if (isFailed) onRetry?.(p)
                    onSelect(p)
                  }}
                  aria-pressed={sel}
                  aria-label={isFailed ? title : t('statements.phone.selectMonth', { month: label(p) })}
                  title={title}
                  data-failed={isFailed || undefined}
                  className="relative h-full shrink-0 rounded-md focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald"
                  style={{ width: track.colW }}
                >
                  {isFailed ? (
                    <span
                      aria-hidden="true"
                      className="absolute left-1/2 grid size-5 -translate-x-1/2 place-items-center rounded-full border border-warning-line bg-surface text-amber"
                      style={{ bottom: height - scale.upperH + 2 }}
                    >
                      <TriangleAlert className="size-3" />
                    </span>
                  ) : null}
                  {!isLine && geo ? (
                    <span
                      aria-hidden="true"
                      className={cn(
                        'bar-up absolute left-1/2 -translate-x-1/2 transition-colors duration-200',
                        geo.anchor === 'bottom' ? 'rounded-t-sm' : 'rounded-b-sm',
                        sel ? TONE_FILL[tone(v as number)] : 'bg-ink-200',
                      )}
                      style={{
                        width: barW,
                        height: geo.h,
                        [geo.anchor]: geo.offset,
                        // Grows out of the baseline it sits on (a negative bar hangs from it).
                        transformOrigin: geo.anchor,
                        animationDelay: `${(i * 0.04).toFixed(2)}s`,
                      }}
                    />
                  ) : null}
                </button>
              )
            })}
          </div>
        </div>

        {/* Month labels — same slots, same scroller, so they can never drift from their column. */}
        <div className="flex" style={{ gap: track.gap, paddingInline: track.inset }}>
          {periods.map((p) => {
            const sel = p === selected
            return (
              <span
                key={p}
                className={cn(
                  'shrink-0 text-center text-2xs leading-none',
                  sel ? 'font-bold text-ink' : 'font-medium text-ink-3',
                )}
                style={{ width: track.colW }}
              >
                {label(p)}
              </span>
            )
          })}
        </div>
      </div>
    </div>
  )
}
