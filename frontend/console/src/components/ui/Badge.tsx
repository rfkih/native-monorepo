import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

type Tone = 'neutral' | 'amber' | 'emerald'

const tones: Record<Tone, string> = {
  neutral: 'bg-paper text-ink-2 border-line-strong',
  amber: 'bg-amber-tint text-amber-2 border-amber/30',
  emerald: 'bg-emerald-tint text-emerald-2 border-emerald/25',
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
        'inline-flex items-center gap-1 rounded-full border px-2.5 py-0.5 text-xs font-medium',
        tones[tone],
        className,
      )}
    >
      {children}
    </span>
  )
}
