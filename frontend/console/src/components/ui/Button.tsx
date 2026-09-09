import type { ButtonHTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

type Variant = 'primary' | 'secondary' | 'outline' | 'ghost'
type Size = 'sm' | 'md' | 'lg' | 'xl' | '2xl'

/**
 * ADR 0077 — the brand is ink, so `primary` is the same colour as body text. What tells an action
 * apart from a heading is the LIFT (`shadow-lift`) plus the press-scale below; neither is polish.
 * `secondary` is deliberately a quiet neutral now rather than a tinted brand fill: with an ink
 * primary there is no lighter brand shade to tint with.
 */
const variants: Record<Variant, string> = {
  primary: 'bg-emerald font-bold text-on-emerald shadow-lift hover:bg-emerald-2 disabled:opacity-50',
  secondary:
    'border border-emerald-line bg-emerald-tint font-bold text-ink hover:bg-line-strong disabled:opacity-50',
  outline: 'border border-line bg-surface text-ink-2 hover:bg-hover hover:text-ink disabled:opacity-50',
  ghost: 'text-ink-2 hover:bg-hover hover:text-ink disabled:opacity-50',
}

/** Radius rides with size: small controls are 12px, the full-width CTAs are 16px. */
const sizes: Record<Size, string> = {
  sm: 'h-10 rounded-xl px-4 text-[13px]',
  md: 'h-11 rounded-xl px-4 text-sm',
  lg: 'h-12 rounded-xl px-5 text-sm',
  xl: 'h-[52px] rounded-2xl px-6 text-[15px]',
  '2xl': 'h-14 rounded-2xl px-6 text-[15.5px]',
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
        'inline-flex items-center justify-center gap-2 font-semibold',
        // Transform joins the transition for the press-scale — `transition-colors` alone made the
        // scale snap. Reduced motion drops the movement but keeps the colour feedback.
        'transition-[color,background-color,border-color,box-shadow,transform] duration-150',
        'active:scale-[0.98] motion-reduce:transition-colors motion-reduce:active:scale-100',
        'focus-visible:outline-2 focus-visible:outline-offset-2',
        'focus-visible:outline-emerald disabled:cursor-not-allowed disabled:active:scale-100',
        sizes[size],
        variants[variant],
        className,
      )}
      {...props}
    />
  )
}
