import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, XAxis } from 'recharts'
import { Card } from '@/components/ui/Card'
import { cn } from '@/lib/cn'
import { formatAmount, formatMoney, minorToMajor } from '@/lib/money'
import { formatPeriod } from '@/lib/period'

// Shared chart colours — the same palette the consolidated Dashboard uses, plus a slate/gold for the
// balance-sheet's three-way composition (assets / liabilities / equity).
export const EMERALD = '#0d6a4a'
export const ROSE = '#8f322b'
export const SLATE = '#3f5a73'
export const GOLD = '#8a6d2f'

/** Month stepper (prev/next around a localized YYYY-MM label). Shared by both statement pages. */
export function PeriodNav({
  period,
  locale,
  onPrev,
  onNext,
  prevLabel,
  nextLabel,
}: {
  period: string
  locale: string
  onPrev: () => void
  onNext: () => void
  prevLabel: string
  nextLabel: string
}) {
  return (
    <div className="inline-flex items-center gap-1 rounded-lg border border-line-strong bg-surface p-1">
      <button
        type="button"
        aria-label={prevLabel}
        onClick={onPrev}
        className="grid size-8 place-items-center rounded-md text-ink-3 transition-colors hover:bg-paper hover:text-ink"
      >
        <ChevronLeft className="size-4" />
      </button>
      <span className="min-w-[8.5rem] text-center text-sm font-medium text-ink">
        {formatPeriod(period, locale)}
      </span>
      <button
        type="button"
        aria-label={nextLabel}
        onClick={onNext}
        className="grid size-8 place-items-center rounded-md text-ink-3 transition-colors hover:bg-paper hover:text-ink"
      >
        <ChevronRight className="size-4" />
      </button>
    </div>
  )
}

/** A single headline figure (minor units + currency), with a loading skeleton. */
export function KpiTile({
  label,
  minor,
  currency,
  locale,
  loading,
  tone,
  emphatic,
}: {
  label: string
  minor: number
  currency: string
  locale: string
  loading: boolean
  tone?: string
  emphatic?: boolean
}) {
  return (
    <Card className={cn('p-5', emphatic && 'ring-1 ring-emerald/15')}>
      <div className="text-[11px] uppercase tracking-wider text-ink-3">{label}</div>
      {loading ? (
        <div className="mt-3 h-7 w-28 animate-pulse rounded bg-line" />
      ) : (
        <div className={cn('tnum mt-2 font-mono text-2xl font-medium', tone ?? 'text-ink')}>
          {formatMoney(minor, currency, locale)}
        </div>
      )}
    </Card>
  )
}

/** A grouped bar of headline figures (e.g. revenue vs expense; assets / liabilities / equity). */
export function SummaryBars({
  data,
  currency,
}: {
  data: { label: string; minor: number; fill: string }[]
  currency: string
}) {
  const chartData = data.map((d) => ({
    label: d.label,
    major: minorToMajor(d.minor, currency),
    fill: d.fill,
  }))
  return (
    <ResponsiveContainer width="100%" height="100%">
      <BarChart
        data={chartData}
        margin={{ top: 8, right: 8, left: 8, bottom: 0 }}
        barCategoryGap="30%"
      >
        <CartesianGrid vertical={false} stroke="#e6dfd1" />
        <XAxis
          dataKey="label"
          tickLine={false}
          axisLine={{ stroke: '#d2c9b6' }}
          tick={{ fill: '#847c6e', fontSize: 12 }}
        />
        <Bar dataKey="major" radius={[6, 6, 0, 0]} maxBarSize={120} isAnimationActive>
          {chartData.map((entry) => (
            <Cell key={entry.label} fill={entry.fill} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  )
}

/** A normalized statement line for the detail tables. */
export interface DisplayLine {
  accountCode: string
  /** Optional friendly label (e.g. localized "Retained earnings"); falls back to the account code. */
  label?: string
  amountMinor: number
}

/** One titled account-line table with a footer total — a section of the line-items disclosure. */
export function LineSection({
  heading,
  lines,
  totalLabel,
  totalMinor,
  currency,
  locale,
  emptyLabel,
}: {
  heading: string
  lines: DisplayLine[]
  totalLabel: string
  totalMinor: number
  currency: string
  locale: string
  emptyLabel: string
}) {
  return (
    <div>
      <div className="mb-1 text-[11px] font-medium uppercase tracking-wider text-ink-3">
        {heading}
      </div>
      <table className="w-full text-sm">
        <tbody>
          {lines.length === 0 ? (
            <tr>
              <td className="py-1.5 text-ink-3">{emptyLabel}</td>
              <td />
            </tr>
          ) : (
            lines.map((line) => (
              <tr key={line.accountCode} className="border-b border-line/60">
                <td className="py-1.5 text-ink-2">{line.label ?? line.accountCode}</td>
                <td className="tnum py-1.5 text-right font-mono text-ink">
                  {formatAmount(line.amountMinor, currency, locale)}
                </td>
              </tr>
            ))
          )}
        </tbody>
        <tfoot>
          <tr className="border-t border-line-strong">
            <td className="py-1.5 font-medium text-ink">{totalLabel}</td>
            <td className="tnum py-1.5 text-right font-mono font-medium text-ink">
              {formatAmount(totalMinor, currency, locale)}
            </td>
          </tr>
        </tfoot>
      </table>
    </div>
  )
}

/** Shared "no company / no data" panel. */
export function StatementEmptyState({ title, hint }: { title: string; hint: string }) {
  return (
    <Card className="mx-auto max-w-md p-10 text-center">
      <h2 className="font-display text-xl font-semibold text-ink">{title}</h2>
      <p className="mt-2 text-sm text-ink-3">{hint}</p>
    </Card>
  )
}
