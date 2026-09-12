/**
 * The motion language's two facts that components need in JS (the rest is CSS — index.css @theme):
 * how long an exit takes, so an overlay can delay its unmount until the animation has run; and
 * whether the user asked for no motion, in which case the delay is skipped, not waited out — a
 * dialog that can only be closed by users who allow animation is not a dialog.
 */

/** Keep in step with --animate-dialog-out / --animate-sheet-down / --animate-scrim-out. */
export const EXIT_MS = 160

export function prefersReducedMotion(): boolean {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
  } catch {
    return false
  }
}

/** The delay to give an exit before unmounting: the animation's length, or none under reduced motion. */
export function exitDelayMs(): number {
  return prefersReducedMotion() ? 0 : EXIT_MS
}
