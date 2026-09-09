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
 * Brand lockup: a solid ink mark with an upward trend line, beside the stacked "Native / CONSOLE"
 * wordmark. Used in the sidebar header (and anywhere the product needs to identify itself).
 *
 * The mark was a cyan gradient. Once the brand went ink (ADR 0077) it was the only cyan left on
 * any screen, which reads as a leftover rather than as a logo — so it takes the primary fill and
 * inverts with it, exactly like every other brand surface. The BINARY marks (Android launcher
 * icons, favicon, OG image, Play Store assets) are still the old cyan and are tracked separately;
 * they are generated artwork, not CSS.
 */
export function Wordmark({ className }: { className?: string }) {
  return (
    <span className={cn('flex items-center gap-[11px]', className)}>
      <span className="grid size-[34px] place-items-center rounded-xl bg-emerald text-on-emerald">
        {/* currentColor, not the #fff default: on dark the tile is near-white, and a white glyph
            on it would be invisible. Other callers keep the default deliberately. */}
        <BrandMark stroke="currentColor" />
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
