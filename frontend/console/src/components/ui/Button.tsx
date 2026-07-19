import type { ButtonHTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

type Variant = 'primary' | 'outline' | 'ghost'

const variants: Record<Variant, string> = {
  primary: 'bg-brand-500 text-white shadow-sm hover:bg-brand-600 disabled:opacity-50',
  outline: 'border border-line bg-surface text-ink hover:bg-hover disabled:opacity-50',
  ghost: 'text-ink-2 hover:bg-hover hover:text-ink disabled:opacity-50',
}

export function Button({
  variant = 'primary',
  className,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return (
    <button
      className={cn(
        'inline-flex items-center justify-center gap-2 rounded-xl px-4 py-2.5 text-sm font-semibold',
        'transition-colors duration-150 focus-visible:outline-2 focus-visible:outline-offset-2',
        'focus-visible:outline-brand-500 disabled:cursor-not-allowed',
        variants[variant],
        className,
      )}
      {...props}
    />
  )
}
