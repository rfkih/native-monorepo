import { cn } from '@/lib/cn'

/**
 * The brand glyph — the upward trend line. ONE owner for the path so a logo revision can't
 * leave stale copies behind; tiles/colors are the caller's concern.
 */
export function BrandMark({
  size = 20,
  stroke = '#fff',
  strokeWidth = 2.4,
}: {
  size?: number
  stroke?: string
  strokeWidth?: number
}) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke={stroke}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M4 18 L10 10 L14 14 L20 5" />
    </svg>
  )
}

/**
 * Brand lockup: a gradient mark with an upward trend line, beside the stacked "Native / CONSOLE"
 * wordmark. Used in the sidebar header (and anywhere the product needs to identify itself).
 */
export function Wordmark({ className }: { className?: string }) {
  return (
    <span className={cn('flex items-center gap-[11px]', className)}>
      <span className="grid size-[34px] place-items-center rounded-xl bg-gradient-to-br from-brand-500 to-brand-800 shadow-sm">
        <BrandMark />
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
