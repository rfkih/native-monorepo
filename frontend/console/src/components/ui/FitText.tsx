/**
 * FitText — a one-line figure that SHRINKS to its box instead of truncating or wrapping.
 *
 * Money is the reason this exists (rule 8's UI corollary): "Rp 28.73…" is a different number,
 * and "Rp 1.250.\n000" reads as two. A KPI tile on a 360px phone is ~125px wide, a big-figure
 * tile in the employee app ~120px; a twelve-character rupiah figure at the token size does not
 * fit either, and the right size depends on the figure, not the tile. So the size stays the
 * TOKEN (`text-xl`, `text-3xl` — the design-token gate still applies) and this only scales it
 * DOWN, to `min` × the token at the most, when the text is wider than the box. It never scales up.
 *
 * Measures with `scrollWidth`, so it is exact for the font that actually rendered. Re-fits when
 * the box resizes (a rotation, the sidebar collapsing) and whenever the children change.
 */
import { useLayoutEffect, useRef, type ReactNode } from 'react'
import { cn } from '@/lib/cn'

export function FitText({
  children,
  className,
  min = 0.6,
}: {
  children: ReactNode
  className?: string
  /** Floor for the scale factor — below it the figure is allowed to overflow (never wrap). */
  min?: number
}) {
  const ref = useRef<HTMLSpanElement>(null)

  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const fit = () => {
      // Reset to the class-driven size first: the previous fit's inline size would otherwise
      // make a figure that no longer needs shrinking measure as if it did.
      el.style.fontSize = ''
      const need = el.scrollWidth
      const have = el.clientWidth
      if (need > have && have > 0) {
        const base = parseFloat(getComputedStyle(el).fontSize)
        el.style.fontSize = `${Math.max(min, have / need) * base}px`
      }
    }
    fit()
    // The box's width never changes when the web font lands, so the observer would not fire —
    // but the glyphs get wider. Fit once more when the fonts are in.
    let live = true
    document.fonts?.ready.then(() => live && fit())
    const ro = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(fit)
    ro?.observe(el)
    return () => {
      live = false
      ro?.disconnect()
    }
  }, [children, min])

  // `contain-inline-size`: the figure contributes NOTHING to its container's intrinsic width. Without
  // it a nowrap figure widens its own grid/flex cell to fit itself (min-content), the cell then
  // measures as "fits", and the card pokes out of the page instead — the fit is circular.
  return (
    <span ref={ref} className={cn('block min-w-0 whitespace-nowrap contain-inline-size', className)}>
      {children}
    </span>
  )
}
