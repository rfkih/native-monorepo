// The month stepper, money tile, and empty panel are shared with the Dashboard — one source so
// the finance surfaces can't drift. Re-exported here so the statements pages import everything
// statement-related from a single module.
export { KpiTile, PeriodNav, EmptyState as StatementEmptyState } from '@/features/_shared/financeUi'

import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronRight } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import { accountLabel } from './accountLabels'

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

/**
 * The 2b/2c statement summary card: color chip + uppercase label + mono figure (+ optional note).
 * When `onClick` is set the whole card becomes a keyboard-accessible button that opens a detail view
 * (the P&L drill-down) — with a hover/focus affordance + a `detailLabel` chevron hint that is hidden
 * in print. Callers that omit `onClick` render exactly as before (a static, non-interactive card).
 */
export function SummaryCard({
  chipClass,
  label,
  value,
  valueClass,
  note,
  noteClass,
  emphatic,
  onClick,
  detailLabel,
}: {
  chipClass: string
  label: string
  value: string
  valueClass?: string
  note?: string
  noteClass?: string
  emphatic?: boolean
  onClick?: () => void
  detailLabel?: string
}) {
  const padding = emphatic
    ? 'border-transparent p-5 shadow-md outline outline-2 -outline-offset-2 outline-brand-100 print:outline-0'
    : 'p-5'

  const body = (
    <>
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
    </>
  )

  if (onClick) {
    return (
      <button
        type="button"
        onClick={onClick}
        className={cn(
          'rounded-card border border-line bg-surface text-left shadow-sm transition-shadow',
          'w-full hover:shadow-md focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
          'print:break-inside-avoid print:shadow-none',
          padding,
        )}
      >
        {body}
        {detailLabel ? (
          <span className="mt-2.5 inline-flex items-center gap-0.5 text-[11px] font-semibold text-brand-600 print:hidden">
            {detailLabel}
            <ChevronRight className="size-3.5" aria-hidden />
          </span>
        ) : null}
      </button>
    )
  }

  return <Card className={`print:break-inside-avoid ${padding}`}>{body}</Card>
}

/** A normalized statement line for the detail tables. */
export interface DisplayLine {
  accountCode: string
  /**
   * Overrides the name looked up from the account code — used by rows that are NOT chart accounts
   * (the cash-flow net-income row, disposal proceeds), which carry an empty `accountCode` and
   * supply their own localized label.
   */
  label?: string
  amountMinor: number
  /**
   * Marks a figure that cannot be real and needs someone to look at it (an asset gone negative).
   * The row takes the loss tone instead of rendering in the same ink as every sound figure.
   */
  flagged?: boolean
  /**
   * Kept out of the way on screen but PRINTED. A statement on paper is a record: dropping accounts
   * from it (the zero balances the page hides) with no trace would leave the printout showing fewer
   * accounts than the ledger holds, and the on-screen disclosure is itself `print:hidden`.
   */
  printOnly?: boolean
}

/** A labelled run of lines inside one section, with its own subtotal (the Neraca's asset groups). */
export interface DisplayGroup {
  label: string
  lines: DisplayLine[]
  subtotalMinor: number
}

/**
 * One row: the NAME leads and the account code trails as a quiet chip. The code used to hold a
 * fixed 96px column at the start of the row — the leftmost, most-read position spent on the one
 * thing a reader told us they don't understand, and 27% of the row on a 360px phone. A line with
 * no name yet (a newly seeded account) shows its code alone rather than twice.
 */
function AccountRow({
  line,
  currency,
  locale,
  format,
  translate,
}: {
  line: DisplayLine
  currency: string
  locale: string
  format: (minor: number, currency: string, locale: string) => string
  translate: (key: string) => string
}) {
  const name = line.label ?? accountLabel(translate, line.accountCode)
  return (
    <div
      className={cn(
        'flex items-center gap-3 border-b border-ink-50 py-[9px] last:border-0 print:break-inside-avoid',
        line.flagged && 'rounded-lg border-b-transparent bg-tint-loss',
        line.printOnly && 'hidden print:flex',
      )}
    >
      {/* On a phone the name WRAPS rather than truncating — there is no width to spare, and a
          clipped "Utang potongan gaji …" hides the one thing the reader came for — and the code
          rides beside it, dropping to the next line only when it doesn't fit. From `sm` up the row
          is wide enough for one line: the name truncates, and the code sits OUTSIDE that truncating
          span so it is never the part that gets clipped. */}
      <span className="flex min-w-0 flex-1 flex-wrap items-baseline gap-x-1.5 sm:flex-nowrap">
        <span className="text-sm text-ink-2 sm:truncate">{name ?? line.accountCode}</span>
        {name && line.accountCode ? (
          <span className="font-mono text-[11px] text-ink-3 sm:shrink-0">{line.accountCode}</span>
        ) : null}
      </span>
      <span
        className={cn(
          'tnum shrink-0 font-mono text-sm',
          line.flagged ? 'font-semibold text-loss' : 'text-ink',
        )}
      >
        {format(line.amountMinor, currency, locale)}
      </span>
    </div>
  )
}

/**
 * One titled account-line table with a footer total — a section of the line-items disclosure.
 *
 * Rows come either flat (`lines`) or in labelled runs with their own subtotals (`groups`), which
 * the Neraca uses to order assets by how quickly each turns into money instead of by account code.
 * The footer total always carries the currency symbol: it is the figure people quote, and the rows
 * above it stay bare so the column packs tight.
 */
interface LineSectionBase {
  heading: string
  totalLabel: string
  totalMinor: number
  currency: string
  locale: string
  emptyLabel: string
  /** Formats the ROW and SUBTOTAL figures. The footer total always carries the currency symbol. */
  format: (minor: number, currency: string, locale: string) => string
  /** Rendered under the total — the Neraca's "n accounts worth nothing are hidden" reveal. */
  footnote?: ReactNode
}

/**
 * Rows come EITHER flat or grouped, never both and never neither — a union rather than two optional
 * props, so "passed neither" (a section that silently renders "no accounts" over real data) and
 * "passed both" (one of them silently dropped) are compile errors instead of blank statements.
 */
export type LineSectionProps = LineSectionBase &
  (
    | { lines: DisplayLine[]; groups?: never }
    | { groups: DisplayGroup[]; lines?: never }
  )

export function LineSection({
  heading,
  lines,
  groups,
  totalLabel,
  totalMinor,
  currency,
  locale,
  emptyLabel,
  format,
  footnote,
}: LineSectionProps) {
  // One subscription for the whole table rather than one per row.
  const { t } = useTranslation()
  const flat = lines ?? []
  const isEmpty = groups ? groups.length === 0 : flat.length === 0
  const rowProps = { currency, locale, format, translate: t }

  return (
    <div>
      {/* Print pagination: the heading keeps its first rows, each row stays whole, and the
          footer total never strands alone on a fresh page (UAT 2026-08-06). */}
      <div className="text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3 print:break-after-avoid">
        {heading}
      </div>
      <div className="mt-2.5">
        {isEmpty ? (
          <div className="py-2 text-sm text-ink-3">{emptyLabel}</div>
        ) : groups ? (
          groups.map((group, index) => (
            <div key={group.label} className={index > 0 ? 'mt-3.5' : undefined}>
              <div className="flex items-baseline gap-3 pb-1 text-[12px] text-ink-3">
                <span className="min-w-0 flex-1 truncate font-semibold">{group.label}</span>
                <span className="tnum shrink-0 font-mono">
                  {format(group.subtotalMinor, currency, locale)}
                </span>
              </div>
              {group.lines.map((line) => (
                <AccountRow key={line.accountCode} line={line} {...rowProps} />
              ))}
            </div>
          ))
        ) : (
          flat.map((line) => <AccountRow key={line.accountCode} line={line} {...rowProps} />)
        )}
      </div>
      <div className="mt-1 flex items-center gap-3 border-t-[1.5px] border-line-strong pt-3 print:break-inside-avoid">
        <span className="min-w-0 flex-1 text-sm font-semibold text-ink">{totalLabel}</span>
        <span className="tnum shrink-0 font-mono text-sm font-semibold text-ink">
          {formatMoney(totalMinor, currency, locale)}
        </span>
      </div>
      {footnote ? <div className="pt-2">{footnote}</div> : null}
    </div>
  )
}
