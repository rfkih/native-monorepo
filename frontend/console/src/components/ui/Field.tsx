import type { InputHTMLAttributes, ReactNode } from 'react'
import { cn } from '@/lib/cn'

export function Field({
  label,
  hint,
  error,
  htmlFor,
  children,
}: {
  label: string
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

export function TextInput({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      className={cn(
        'w-full rounded-xl border border-line bg-surface px-3.5 py-3 text-sm text-ink',
        'transition-colors placeholder:text-ink-3/70',
        'focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-500/12',
        className,
      )}
      {...props}
    />
  )
}
