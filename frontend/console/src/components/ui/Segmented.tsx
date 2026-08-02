import { cn } from '@/lib/cn'

export function Segmented<T extends string>({
  options,
  value,
  onChange,
  ariaLabel,
  className,
}: {
  options: {
    value: T
    label: string
    /** Renders the option greyed-out and unclickable — a real HTML `disabled` (not just visual),
     * so it drops out of both click and keyboard-tab reach. */
    disabled?: boolean
    /** Tooltip explaining WHY, shown when `disabled` (e.g. "needs a connection"). */
    title?: string
  }[]
  value: T
  onChange: (value: T) => void
  ariaLabel?: string
  className?: string
}) {
  return (
    <div
      role="tablist"
      aria-label={ariaLabel}
      className={cn('inline-flex h-10 items-center gap-[3px] rounded-xl bg-ink-50 p-1', className)}
    >
      {options.map((option) => {
        const active = option.value === value
        return (
          <button
            key={option.value}
            type="button"
            role="tab"
            aria-selected={active}
            disabled={option.disabled}
            title={option.disabled ? option.title : undefined}
            onClick={() => onChange(option.value)}
            className={cn(
              'grid h-8 place-items-center rounded-lg px-4 text-[13px] transition-colors',
              'disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:text-ink-3',
              active
                ? 'bg-surface font-bold text-ink shadow-sm'
                : 'font-semibold text-ink-3 hover:text-ink-2',
            )}
          >
            {option.label}
          </button>
        )
      })}
    </div>
  )
}
