import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

export interface Choice<T extends string> {
  value: T
  title: string
  subtitle?: string
  aside?: ReactNode
  /** Renders the card greyed-out and unselectable — a real `disabled` radio input (drops out of
   *  both click and keyboard-tab reach), not just a visual dimming. `subtitle` is still shown, so
   *  the caller can swap it for an explanatory hint when passing `disabled: true`. */
  disabled?: boolean
}

export function ChoiceCards<T extends string>({
  name,
  options,
  value,
  onChange,
  columns = 2,
  onDeselect,
}: {
  name: string
  options: Choice<T>[]
  value: T
  onChange: (value: T) => void
  columns?: 1 | 2
  /** When provided, clicking the already-selected card clears the selection by invoking this
   *  callback (instead of `onChange`, which would need to fabricate a `T`). Radios don't fire
   *  `change` on re-click, so this is wired via `onClick`. Omit it to keep the pure single-choice
   *  (radio) semantics for callers that require exactly one selection. */
  onDeselect?: () => void
}) {
  return (
    <div className={cn('grid gap-2.5', columns === 2 && 'sm:grid-cols-2')}>
      {options.map((option) => {
        const active = option.value === value
        return (
          <label
            key={option.value}
            className={cn(
              'relative flex items-start gap-3 rounded-2xl border p-4 transition-all',
              option.disabled
                ? 'cursor-not-allowed border-line bg-surface opacity-55'
                : cn(
                    'cursor-pointer',
                    active
                      ? 'border-emerald bg-emerald-tint ring-4 ring-emerald/15'
                      : 'border-line bg-surface hover:border-ink-3',
                  ),
            )}
          >
            <input
              type="radio"
              name={name}
              value={option.value}
              checked={active}
              disabled={option.disabled}
              onChange={() => onChange(option.value)}
              onClick={onDeselect && active && !option.disabled ? onDeselect : undefined}
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
