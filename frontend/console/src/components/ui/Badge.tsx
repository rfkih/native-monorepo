import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

type Tone = 'neutral' | 'amber' | 'emerald' | 'info' | 'loss'

const tones: Record<Tone, string> = {
  neutral: 'bg-ink-50 text-ink-2',
  amber: 'bg-tint-warning text-amber-2',
  emerald: 'bg-tint-profit text-brand-700',
  info: 'bg-tint-info text-info',
  loss: 'bg-tint-loss text-loss',
}

export function Badge({
  tone = 'neutral',
  className,
  children,
}: {
  tone?: Tone
  className?: string
  children: ReactNode
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold',
        tones[tone],
        className,
      )}
    >
      {children}
    </span>
  )
}
