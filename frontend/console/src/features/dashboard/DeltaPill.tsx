/**
 * DeltaPill — the ▲/▼ percent pill, extracted verbatim from Dashboard.tsx (pure code motion)
 * so the phone dashboard renders the identical delta treatment.
 */
import { cn } from '@/lib/cn'
import { formatPercent } from '@/lib/money'

export function DeltaPill({ value, locale }: { value: number; locale: string }) {
  const up = value >= 0
  return (
    <span
      className={cn(
        'tnum inline-flex items-center gap-1 rounded-full px-3 py-1.5 font-mono text-[13px] font-bold',
        up ? 'bg-tint-profit text-profit-ink' : 'bg-tint-loss text-loss',
      )}
    >
      {up ? '▲' : '▼'} {formatPercent(Math.abs(value), locale)}
    </span>
  )
}
