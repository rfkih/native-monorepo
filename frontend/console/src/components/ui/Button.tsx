import type { ButtonHTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

type Variant = 'primary' | 'outline' | 'ghost'

const variants: Record<Variant, string> = {
  primary: 'bg-emerald text-white hover:bg-emerald-2 disabled:opacity-50',
  outline: 'border border-line-strong bg-surface text-ink hover:border-ink-3 disabled:opacity-50',
  ghost: 'text-ink-2 hover:bg-emerald-tint/60 hover:text-ink disabled:opacity-50',
}

export function Button({
  variant = 'primary',
  className,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return (
    <button
      className={cn(
        'inline-flex items-center justify-center gap-2 rounded-lg px-4 py-2.5 text-sm font-medium',
        'transition-colors duration-150 focus-visible:outline-2 focus-visible:outline-offset-2',
        'focus-visible:outline-emerald disabled:cursor-not-allowed',
        variants[variant],
        className,
      )}
      {...props}
    />
  )
}
