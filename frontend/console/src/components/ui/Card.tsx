import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        'rounded-card border border-line bg-surface',
        'shadow-[0_1px_2px_rgba(27,24,19,0.04),0_18px_40px_-28px_rgba(27,24,19,0.30)]',
        className,
      )}
      {...props}
    />
  )
}
