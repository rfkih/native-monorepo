// The month stepper, money tile, and empty panel are shared with the Dashboard — one source so
// the finance surfaces can't drift. Re-exported here so the statements pages import everything
// statement-related from a single module.
export { KpiTile, PeriodNav, EmptyState as StatementEmptyState } from '@/features/_shared/financeUi'

import { Card } from '@/components/ui/Card'

/**
 * The reporting-entity line every formal statement leads with: WHICH business these figures belong
 * to, and that they cover the whole company (the GL has no business-unit dimension — per-unit P&L
 * lives on the org-unit pages). Rendered above the statement title; included when printing.
 */
export function EntityScope({ name, scope }: { name: string; scope: string }) {
  return (
    <div className="mb-1 text-[13px] font-semibold text-ink-2">
      {name} <span className="font-normal text-ink-3">· {scope}</span>
    </div>
  )
}

/** The 2b/2c statement summary card: color chip + uppercase label + mono figure (+ optional note). */
export function SummaryCard({
  chipClass,
  label,
  value,
  valueClass,
  note,
  noteClass,
  emphatic,
}: {
  chipClass: string
  label: string
  value: string
  valueClass?: string
  note?: string
  noteClass?: string
  emphatic?: boolean
}) {
  return (
    <Card
      className={`print:break-inside-avoid ${
        emphatic
          ? 'border-transparent p-5 shadow-md outline outline-2 -outline-offset-2 outline-brand-100 print:outline-0'
          : 'p-5'
      }`}
    >
      <div className="flex items-center gap-2">
        <span className={`size-2.5 rounded-[3px] ${chipClass}`} aria-hidden />
        <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
          {label}
        </span>
      </div>
      <div
        className={`tnum mt-2.5 font-mono text-[26px] leading-tight print:text-xl ${
          emphatic ? 'font-bold' : 'font-semibold'
        } ${valueClass ?? 'text-ink'}`}
      >
        {value}
      </div>
      {note ? (
        <div className={`mt-1.5 text-xs font-semibold ${noteClass ?? 'text-ink-3'}`}>{note}</div>
      ) : null}
    </Card>
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
  format,
}: {
  heading: string
  lines: DisplayLine[]
  totalLabel: string
  totalMinor: number
  currency: string
  locale: string
  emptyLabel: string
  format: (minor: number, currency: string, locale: string) => string
}) {
  return (
    <div>
      {/* Print pagination: the heading keeps its first rows, each row stays whole, and the
          footer total never strands alone on a fresh page (UAT 2026-08-06). */}
      <div className="mb-2.5 text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3 print:break-after-avoid">
        {heading}
      </div>
      {lines.length === 0 ? (
        <div className="py-2 text-sm text-ink-3">{emptyLabel}</div>
      ) : (
        lines.map((line) => (
          <div
            key={line.accountCode}
            className="flex items-center border-b border-ink-50 py-[9px] last:border-0 print:break-inside-avoid"
          >
            <span className="w-24 shrink-0 font-mono text-xs text-ink-3">{line.accountCode}</span>
            <span className="flex-1 truncate text-sm text-ink-2">
              {line.label ?? line.accountCode}
            </span>
            <span className="tnum font-mono text-sm text-ink">
              {format(line.amountMinor, currency, locale)}
            </span>
          </div>
        ))
      )}
      <div className="mt-1 flex items-center border-t-[1.5px] border-line-strong pt-3 print:break-inside-avoid">
        <span className="flex-1 text-sm font-semibold text-ink">{totalLabel}</span>
        <span className="tnum font-mono text-sm font-semibold text-ink">
          {format(totalMinor, currency, locale)}
        </span>
      </div>
    </div>
  )
}
