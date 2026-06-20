import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, XAxis } from 'recharts'
import { formatAmount, minorToMajor } from '@/lib/money'

// The month stepper, money tile, empty panel, and base palette are shared with the Dashboard — one
// source so the finance surfaces can't drift. Re-exported here so the statements pages import
// everything statement-related from a single module.
export { EMERALD, ROSE, KpiTile, PeriodNav, EmptyState as StatementEmptyState } from '@/features/_shared/financeUi'

// Balance-sheet composition palette (assets / liabilities / equity) — statement-specific.
export const SLATE = '#3f5a73'
export const GOLD = '#8a6d2f'

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
