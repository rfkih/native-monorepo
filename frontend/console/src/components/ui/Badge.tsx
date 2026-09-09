import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

/**
 * Two badge families (ADR 0077), and the distinction carries meaning:
 *
 *  - `outline` (the DEFAULT) — transparent fill, coloured hairline, coloured text. This is a
 *    STATUS: Approved / Submitted / Rejected / Inactive. Quiet enough to sit several-to-a-row
 *    without the row turning into a paint chart.
 *  - `solid` — the tinted fill. Reserved for a COUNT or a single emphasised fact (a pending
 *    total, "Active"), where the badge is the thing you are meant to see.
 *
 * `emerald` is the BRAND tone (now ink) — use `profit` for anything that means money made or work
 * done (Paid / Sent / Active): green is reserved for profit, and nothing else.
 */
type Tone = 'neutral' | 'amber' | 'emerald' | 'profit' | 'info' | 'loss'
type Variant = 'outline' | 'solid'

const solid: Record<Tone, string> = {
  neutral: 'bg-ink-50 text-ink-2',
  amber: 'bg-tint-warning text-amber-2',
  emerald: 'bg-emerald-tint text-ink',
  profit: 'bg-tint-profit text-profit-ink',
  info: 'bg-tint-info text-info',
  loss: 'bg-tint-loss text-loss',
}

const outline: Record<Tone, string> = {
  neutral: 'border border-line-strong text-ink-3',
  amber: 'border border-warning-line text-amber-2',
  emerald: 'border border-line-strong text-ink',
  profit: 'border border-profit-line text-profit-ink',
  info: 'border border-info-line text-info',
  loss: 'border border-loss-line text-loss-ink',
}

export function Badge({
  tone = 'neutral',
  variant = 'outline',
  className,
  children,
}: {
  tone?: Tone
  variant?: Variant
  className?: string
  children: ReactNode
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold',
        variant === 'solid' ? solid[tone] : outline[tone],
        className,
      )}
    >
      {children}
    </span>
  )
}
