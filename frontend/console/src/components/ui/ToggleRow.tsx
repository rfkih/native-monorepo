import { cn } from '@/lib/cn'

/**
 * A labelled on/off switch row — label + hint on the left, the pill switch on the right. Extracted
 * from `features/settings/PrinterSettings.tsx` (its original home) so `features/settings/
 * FeaturesSettings.tsx` (P1 tier-mode toggle) can share it instead of duplicating the markup.
 *
 * Accessible as a native `<button role="switch">` (announces on/off via `aria-checked`, focusable
 * and activatable by keyboard with no extra wiring) with a visible focus ring (quality floor).
 */
export function ToggleRow({
  label,
  hint,
  checked,
  onToggle,
  disabled,
}: {
  label: string
  hint: string
  checked: boolean
  onToggle: () => void
  disabled?: boolean
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      disabled={disabled}
      onClick={onToggle}
      className={cn(
        'flex items-center justify-between gap-3 rounded-lg text-left',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
        disabled && 'cursor-not-allowed opacity-60',
      )}
    >
      <span>
        <span className="block text-sm font-semibold text-ink">{label}</span>
        <span className="block text-xs text-ink-3">{hint}</span>
      </span>
      <span
        aria-hidden
        className={cn(
          'relative h-6 w-10 shrink-0 rounded-full transition-colors',
          checked ? 'bg-brand-500' : 'bg-line',
        )}
      >
        <span
          className={cn(
            'absolute top-0.5 size-5 rounded-full bg-white shadow transition-all',
            checked ? 'left-[18px]' : 'left-0.5',
          )}
        />
      </span>
    </button>
  )
}
