/**
 * CategoryRail.tsx — extracted VERBATIM from Pos.tsx (redesign P2, mechanical move only).
 * Markup and behavior unchanged; closures became props where needed.
 */
import type { } from '@/lib/session'
import { cn } from '@/lib/cn'
import type { } from '../lib/categories'
import type { } from '@/features/loyalty/api'


// ---------------------------------------------------------------------------
// CategoryCell — left rail cell (76px tall)
// ---------------------------------------------------------------------------

export function CategoryCell({
  label,
  icon,
  active,
  onClick,
}: {
  label: string
  icon: React.ReactNode
  active: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        'relative flex h-[76px] w-full flex-col items-center justify-center gap-1.5 transition-colors focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald',
        !active && 'hover:bg-ink-50',
      )}
    >
      {/* Active = a 3px brand indicator bar + tinted icon — quieter than a full cell fill (P4). */}
      {active ? (
        <span
          className="absolute left-0 top-1/2 h-9 w-[3px] -translate-y-1/2 rounded-r-full bg-emerald"
          aria-hidden="true"
        />
      ) : null}
      <span className={active ? 'text-emerald-2' : 'text-ink-3'}>{icon}</span>
      <span
        className={cn(
          'text-[11px] leading-tight',
          active ? 'font-bold text-emerald-2' : 'font-semibold text-ink-3',
        )}
      >
        {label}
      </span>
    </button>
  )
}


// ---------------------------------------------------------------------------
// CategoryIcon — generic icon by category name
// ---------------------------------------------------------------------------

export function CategoryIcon({ name }: { name: string }) {
  const lower = name.toLowerCase()
  if (lower.includes('drink') || lower.includes('minum') || lower.includes('beverage')) {
    return (
      <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M5 3h14l-1.5 5H6.5Z" /><path d="M7 8v10a3 3 0 0 0 3 3h4a3 3 0 0 0 3-3V8" />
      </svg>
    )
  }
  if (lower.includes('dessert') || lower.includes('sweet') || lower.includes('pencuci')) {
    return (
      <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M4 19h16" /><path d="M6 19a6 6 0 0 1 12 0" /><path d="M12 9V5" />
      </svg>
    )
  }
  if (lower.includes('grill') || lower.includes('bakar')) {
    return (
      <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M4 20h16" /><path d="M6 16h12" /><path d="M8 16V6" /><path d="M12 16V4" /><path d="M16 16V7" />
      </svg>
    )
  }
  if (lower.includes('side') || lower.includes('pelengkap')) {
    return (
      <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <circle cx="12" cy="12" r="9" /><circle cx="12" cy="12" r="4" />
      </svg>
    )
  }
  // Default: generic food/restaurant icon
  return (
    <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M3 11h18" /><path d="M5 11a7 7 0 0 1 14 0" /><path d="M4 15h16" /><path d="M6 19h12" />
    </svg>
  )
}


export function AllCategoriesIcon() {
  return (
    <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="3" width="7" height="7" rx="1" /><rect x="14" y="3" width="7" height="7" rx="1" />
      <rect x="3" y="14" width="7" height="7" rx="1" /><rect x="14" y="14" width="7" height="7" rx="1" />
    </svg>
  )
}
