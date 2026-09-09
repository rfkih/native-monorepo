import type { ComponentPropsWithRef } from 'react'
import { cn } from '@/lib/cn'

/** `ComponentPropsWithRef` rather than `HTMLAttributes` so a caller can hold the node — Dialog
 *  focuses the card as its panel. React 19 passes `ref` as an ordinary prop, so the spread below
 *  is all the forwarding needed. */
/**
 * Border-only, deliberately (ADR 0077). The page ground is white now, so a resting card is
 * separated by its hairline; spending a shadow on every card flattens the hierarchy and leaves
 * nothing to lift the things that should actually read as raised. Elevation is spent by role:
 * overlays carry `shadow-lg` (Dialog / Drawer / MobileSheet), the primary button carries
 * `shadow-lift`. A card that genuinely needs to float passes its own shadow class.
 */
export function Card({ className, ...props }: ComponentPropsWithRef<'div'>) {
  return <div className={cn('rounded-card border border-line bg-surface', className)} {...props} />
}
