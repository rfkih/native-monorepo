/**
 * ShareBars — a list of accounts, each with its amount, its share of a total, and a 6px bar drawn to
 * that share. The one composition idiom the reports use, in one place: the Income Statement's
 * "largest expenses", the phone Expenses tab's "every expense account", and the drill-down sheet's
 * per-account list all render THIS.
 *
 * Bars are the only coloured ink on the row (dataviz: text wears text tokens, the mark carries the
 * meaning) — and the colour is meaning, not identity (ADR 0077): expense bars are loss red, every
 * other list is ink. Green is profit and nothing else, so revenue is NOT green. The share is clamped
 * to [0, 1] for the bar — a contra line can push a raw share past 100% or below zero — while the
 * printed percentage keeps its true value, so the number never lies to make the picture tidy.
 *
 * `size="md"` is the desktop drawer's row as it has always been; `sm` is the phone's.
 */
import { cn } from '@/lib/cn'
import { formatAmount, formatPercent } from '@/lib/money'

export interface ShareRow {
  /** Stable key — the account code, or a label for a code-less row. */
  key: string
  name: string
  /** Trails the name as a quiet mono chip; empty for a synthetic row. */
  code: string
  amountMinor: number
  /** Fraction of the section total; may exceed [0, 1] for contra lines. */
  share: number
}

export function ShareBars({
  rows,
  currency,
  locale,
  tone,
  size = 'md',
  className,
}: {
  rows: ShareRow[]
  currency: string
  locale: string
  /** Loss ink for expense lists; ink for everything else (revenue included — green means profit). */
  tone: 'ink' | 'loss'
  size?: 'md' | 'sm'
  className?: string
}) {
  const sm = size === 'sm'
  return (
    <div className={cn('flex flex-col', sm ? 'gap-3.5' : 'gap-[15px]', className)}>
      {rows.map((r) => {
        const width = Math.max(0, Math.min(1, r.share)) * 100
        return (
          <div key={r.key}>
            <div className={cn('flex items-baseline', sm ? 'gap-2.5' : 'gap-3')}>
              {/* An account name may take two lines on a phone ("Beban BPJS — bagian perusahaan"
                  next to its amount and share); the ellipsis cut the part that named the account. */}
              <span
                className={cn(
                  'line-clamp-2 min-w-0 flex-1 break-words',
                  sm ? 'text-xs font-semibold leading-[1.3] text-ink' : 'text-sm text-ink-2',
                )}
              >
                {r.name}
                {r.code ? (
                  <span className={cn('ml-1.5 font-mono font-normal text-ink-3', sm ? 'text-2xs' : 'text-2xs')}>
                    {r.code}
                  </span>
                ) : null}
              </span>
              <span
                className={cn(
                  'tnum shrink-0 font-mono font-semibold text-ink',
                  sm ? 'text-xs leading-[1.3]' : 'text-sm',
                )}
              >
                {formatAmount(r.amountMinor, currency, locale)}
              </span>
              <span
                className={cn(
                  'tnum shrink-0 text-right font-mono text-ink-3',
                  sm ? 'w-[38px] text-2xs leading-[1.3]' : 'w-[42px] text-xs',
                )}
              >
                {formatPercent(r.share, locale)}
              </span>
            </div>
            <div className={cn('overflow-hidden rounded-full', sm ? 'mt-1.5 h-1.5 bg-ink-100' : 'mt-1.5 h-2 bg-ink-50')}>
              <div
                className={cn(
                  'h-full rounded-full transition-[width] duration-[420ms] ease-[cubic-bezier(.16,1,.3,1)]',
                  tone === 'loss' ? 'bg-loss' : 'bg-ink',
                )}
                style={{ width: `${width}%` }}
              />
            </div>
          </div>
        )
      })}
    </div>
  )
}
