import type { SelectHTMLAttributes } from 'react'
import { ChevronDown } from 'lucide-react'
import { cn } from '@/lib/cn'

/**
 * Styled NATIVE `<select>` — same field contract as `TextInput` (height, radius, border, the
 * single emerald focus ring). Native over a custom combobox on purpose: keyboard type-ahead,
 * mobile pickers and screen-reader semantics come for free, with zero dependencies.
 */
export function Select({
  className,
  children,
  ...props
}: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <div className={cn('relative', className)}>
      <select
        className={cn(
          'h-[52px] w-full appearance-none rounded-xl border border-line bg-surface pl-4 pr-10',
          'text-[15px] text-ink transition-colors',
          'focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15',
        )}
        {...props}
      >
        {children}
      </select>
      <ChevronDown
        className="pointer-events-none absolute right-3.5 top-1/2 size-4 -translate-y-1/2 text-ink-3"
        aria-hidden
      />
    </div>
  )
}
