import type { InputHTMLAttributes, ReactNode } from 'react'
import { cn } from '@/lib/cn'

export function Field({
  label,
  hint,
  error,
  htmlFor,
  children,
}: {
  /** Usually plain text; a ReactNode is allowed for a label that carries an inline marker (e.g. a
   * "Required" badge next to the text). */
  label: ReactNode
  hint?: string
  error?: string
  htmlFor?: string
  children: ReactNode
}) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={htmlFor} className="block text-sm font-medium text-ink">
        {label}
      </label>
      {children}
      {error ? (
        <p className="text-xs text-rose">{error}</p>
      ) : hint ? (
        <p className="text-xs leading-relaxed text-ink-3">{hint}</p>
      ) : null}
    </div>
  )
}

/**
 * The single focus treatment (audit finding 09): brand-600 border plus a 4px soft halo.
 * Every focusable field in the product uses this ring — nothing else.
 */
export function TextInput({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      className={cn(
        'h-[52px] w-full rounded-xl border border-line bg-surface px-4 text-[15px] text-ink',
        'transition-colors placeholder:text-ink-3/70',
        'focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15',
        className,
      )}
      {...props}
    />
  )
}
