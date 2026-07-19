import { cn } from '@/lib/cn'

/**
 * Brand lockup: a gradient mark with an upward trend line, beside the stacked "Native / CONSOLE"
 * wordmark. Used in the sidebar header (and anywhere the product needs to identify itself).
 */
export function Wordmark({ className }: { className?: string }) {
  return (
    <span className={cn('flex items-center gap-[11px]', className)}>
      <span className="grid size-[34px] place-items-center rounded-[10px] bg-gradient-to-br from-brand-500 to-brand-700 shadow-sm">
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="#fff"
          strokeWidth="2.4"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="M4 18 L10 10 L14 14 L20 5" />
        </svg>
      </span>
      <span className="leading-none">
        <span className="block font-display text-[17px] font-extrabold leading-none tracking-[-0.02em] text-ink">
          Native
        </span>
        <span className="mt-[3px] block text-[9.5px] font-bold tracking-[0.16em] text-ink-3">
          CONSOLE
        </span>
      </span>
    </span>
  )
}
