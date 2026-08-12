import { cn } from '@/lib/cn'

export function Segmented<T extends string>({
  options,
  value,
  onChange,
  ariaLabel,
  className,
  fluid = false,
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
  /** Fill the parent's width and let every segment shrink/grow equally. The default inline
   * control is content-sized and CANNOT shrink, so 4+ options overflow a phone column (the
   * signup company-size row poked past the card edge on an S23). Only for short labels —
   * segments share the row evenly and never wrap. */
  fluid?: boolean
}) {
  return (
    <div
      role="tablist"
      aria-label={ariaLabel}
      className={cn(
        // min-h (not fixed h) so a wrapping variant (className="flex-wrap", e.g. the invoices status
        // filter on a 360px phone) grows to fit its rows instead of the 2nd row spilling out of the
        // box and being overlapped by the next element. min-h-11 (44px) is a comfortable touch row.
        'inline-flex min-h-11 items-center gap-[3px] rounded-xl bg-ink-50 p-1',
        fluid && 'flex w-full',
        className,
      )}
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
              'grid h-9 place-items-center rounded-lg text-[13px] transition-colors',
              fluid ? 'min-w-0 flex-1 px-1' : 'px-4',
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
