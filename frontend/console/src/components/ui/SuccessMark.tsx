import { cn } from '@/lib/cn'

/**
 * The console's ONE "done" moment (motion language, index.css): a profit-green disc that pops in
 * and a check that draws itself — 0.2s + 0.45s, once, when a task the user set out to do is
 * actually complete (a payment captured, a period closed, a count submitted, a card sold). It is
 * the only motion in the app that is not a direct answer to a press, which is exactly why it is
 * reserved for completion and never used for "saved", "loaded", or decoration.
 *
 * Green means done and nothing else (ADR 0077). Respects prefers-reduced-motion: the disc and
 * the check simply appear.
 */
export function SuccessMark({ size = 'md', className }: { size?: 'sm' | 'md' | 'lg'; className?: string }) {
  const disc = size === 'sm' ? 'size-9' : size === 'lg' ? 'size-16' : 'size-12'
  const glyph = size === 'sm' ? 'size-4' : size === 'lg' ? 'size-8' : 'size-6'
  return (
    <span
      aria-hidden="true"
      className={cn('pop grid shrink-0 place-items-center rounded-full bg-tint-profit text-profit-ink', disc, className)}
    >
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth={2.6}
        strokeLinecap="round"
        strokeLinejoin="round"
        className={glyph}
      >
        <path d="M5 12.5l4.5 4.5L19 7.5" pathLength={100} className="draw-check" />
      </svg>
    </span>
  )
}
