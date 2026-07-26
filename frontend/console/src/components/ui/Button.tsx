import type { ButtonHTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

type Variant = 'primary' | 'secondary' | 'outline' | 'ghost'
type Size = 'sm' | 'md' | 'lg' | 'xl'

const variants: Record<Variant, string> = {
  primary: 'bg-emerald font-bold text-on-emerald shadow-sm hover:bg-emerald-2 disabled:opacity-50',
  secondary:
    'border border-emerald-line bg-emerald-tint font-bold text-emerald-2 hover:bg-brand-100/60 disabled:opacity-50',
  outline: 'border border-line bg-surface text-ink hover:bg-hover disabled:opacity-50',
  ghost: 'text-ink-2 hover:bg-hover hover:text-ink disabled:opacity-50',
}

const sizes: Record<Size, string> = {
  sm: 'h-10 px-4 text-[13px]',
  md: 'h-11 px-4 text-sm',
  lg: 'h-12 px-5 text-sm',
  xl: 'h-[54px] px-6 text-[15px]',
}

export function Button({
  variant = 'primary',
  size = 'sm',
  className,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: Size }) {
  return (
    <button
      className={cn(
        'inline-flex items-center justify-center gap-2 rounded-xl font-semibold',
        'transition-colors duration-150 focus-visible:outline-2 focus-visible:outline-offset-2',
        'focus-visible:outline-emerald disabled:cursor-not-allowed',
        sizes[size],
        variants[variant],
        className,
      )}
      {...props}
    />
  )
}
