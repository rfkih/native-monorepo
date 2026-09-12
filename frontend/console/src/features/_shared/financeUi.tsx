import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { FitText } from '@/components/ui/FitText'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import { formatPeriod } from '@/lib/period'

/**
 * Presentational primitives shared by the finance surfaces (the consolidated Dashboard and the
 * Financial Statements pages): the month stepper, a headline money tile, an empty panel, and the
 * shared chart palette. One source so the two surfaces can't drift.
 */


/** Month stepper (prev/next around a localized YYYY-MM label). */
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
    <div className="inline-flex items-center gap-1 rounded-xl border border-line bg-surface p-1">
      <button
        type="button"
        aria-label={prevLabel}
        onClick={onPrev}
        className="grid size-10 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-ink"
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
        className="grid size-10 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-ink"
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
    <Card className={cn('p-5', emphatic && 'outline outline-2 -outline-offset-2 outline-ink')}>
      <div className="text-2xs font-semibold uppercase tracking-eyebrow text-ink-3">{label}</div>
      {loading ? (
        <div className="mt-3 h-7 w-28 animate-pulse rounded bg-ink-100" />
      ) : (
        // The figure fits the tile — a nine-digit rupiah figure at text-2xl is wider than a
        // phone-width card, and neither a wrapped nor a truncated amount is that amount.
        <FitText className={cn('tnum mt-2 font-mono text-2xl font-semibold', tone ?? 'text-ink')}>
          {formatMoney(minor, currency, locale)}
        </FitText>
      )}
    </Card>
  )
}

/** A centered "no company / no data" panel. */
export function EmptyState({ title, hint }: { title: string; hint: string }) {
  return (
    <Card className="mx-auto max-w-md p-10 text-center">
      <h2 className="font-display text-xl font-semibold text-ink">{title}</h2>
      <p className="mt-2 text-sm text-ink-3">{hint}</p>
    </Card>
  )
}
