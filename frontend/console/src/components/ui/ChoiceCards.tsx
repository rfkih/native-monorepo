import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

export interface Choice<T extends string> {
  value: T
  title: string
  subtitle?: string
  aside?: ReactNode
}

export function ChoiceCards<T extends string>({
  name,
  options,
  value,
  onChange,
  columns = 2,
}: {
  name: string
  options: Choice<T>[]
  value: T
  onChange: (value: T) => void
  columns?: 1 | 2
}) {
  return (
    <div className={cn('grid gap-2.5', columns === 2 && 'sm:grid-cols-2')}>
      {options.map((option) => {
        const active = option.value === value
        return (
          <label
            key={option.value}
            className={cn(
              'relative flex cursor-pointer items-start gap-3 rounded-xl border p-3.5 transition-all',
              active
                ? 'border-emerald bg-emerald-tint/50 ring-4 ring-emerald/10'
                : 'border-line-strong bg-surface hover:border-ink-3',
            )}
          >
            <input
              type="radio"
              name={name}
              value={option.value}
              checked={active}
              onChange={() => onChange(option.value)}
              className="sr-only"
            />
            <span
              className={cn(
                'mt-0.5 grid size-4 shrink-0 place-items-center rounded-full border',
                active ? 'border-emerald' : 'border-line-strong',
              )}
            >
              {active ? <span className="size-2 rounded-full bg-emerald" /> : null}
            </span>
            <span className="min-w-0 flex-1">
              <span className="flex items-center justify-between gap-2">
                <span className="font-medium text-ink">{option.title}</span>
                {option.aside}
              </span>
              {option.subtitle ? (
                <span className="mt-0.5 block text-xs text-ink-3">{option.subtitle}</span>
              ) : null}
            </span>
          </label>
        )
      })}
    </div>
  )
}
