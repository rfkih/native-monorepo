/**
 * DialogOverlay — the ONE modal overlay (navigation contract, ADR 0075 rule N3).
 *
 * This is `features/org/parts.tsx`'s version promoted, not a new component: that copy had already
 * won on merit (bottom sheet on phone, centred card on tablet+) and was already being imported
 * across twelve other features, which is also why it needed to move — a feature module is not a
 * place other features should reach into. Five NEARLY identical copies (ap, ar, bank, channels,
 * platform) are gone; they had already drifted (`p-4` on two of them, `max-w-2xl` on one, and a
 * plain centred box on phone where this one gives a sheet).
 *
 * What the copies all got wrong, fixed here once:
 *  - **Escape did nothing.** They put `onKeyDown` on a div with no `tabIndex`, so the handler only
 *    ever fired if focus happened to be inside. Here the panel is focused on open and the handler
 *    sits on the overlay root, so the key always reaches it — and because it is scoped to the
 *    focused subtree rather than `document`, stacked dialogs close one at a time instead of all
 *    at once.
 *  - **No focus management.** Focus stayed on whatever opened the dialog, Tab wandered out behind
 *    the scrim, and nothing was restored on close. Focus now moves in, is trapped, and goes back.
 *  - **No way out.** Every overlay in the app was `{open ? <X/> : null}`, so closing was a
 *    disappearance. A close request now plays the exit and only THEN tells the parent to unmount.
 *
 * The parent still owns mounting — this only delays the moment it is told. Under
 * `prefers-reduced-motion` the delay is skipped entirely rather than waited out, and the timer is
 * cleared on unmount, so a parent that closes for its own reasons is never called twice or late.
 */
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { cn } from '@/lib/cn'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { useScrollLock } from '@/components/mobile/useScrollLock'
import { SAFE_AREA_BOTTOM } from '@/lib/safeArea'
import { Card } from './Card'

/** Keep in step with --animate-dialog-out / --animate-sheet-down in index.css. */
const DIALOG_EXIT_MS = 160

const FOCUSABLE =
  'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])'

function prefersReducedMotion(): boolean {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
  } catch {
    return false
  }
}

export function DialogOverlay({
  children,
  onClose,
  ariaLabel,
  size = 'md',
  className,
}: {
  /** Plain content, or a render function handed the overlay's own close — so a close button drawn
   *  INSIDE the panel plays the same exit as the scrim, Escape and Back, instead of unmounting the
   *  dialog on the spot. */
  children: ReactNode | ((requestClose: () => void) => ReactNode)
  onClose: () => void
  /** Already-translated accessible name for the dialog. */
  ariaLabel?: string
  /** `lg` is the wide variant the bank dialogs used (max-w-2xl); everything else is `md`. */
  size?: 'md' | 'lg'
  /** Extra classes on the panel, merged LAST — a content that lays itself out edge to edge (a
   *  keypad sheet, a list with hairline rows) passes `p-0` and owns its padding — INCLUDING the
   *  phone's nav-bar inset (lib/safeArea), which the default-padded panel adds by itself. */
  className?: string
}) {
  const panelRef = useRef<HTMLDivElement>(null)
  const [exiting, setExiting] = useState(false)
  // `p-0` is the documented "I lay out my own edges" signal; every other panel is a padded card
  // whose last row would otherwise sit under the Android nav bar on the phone.
  const ownsEdges = (className ?? '').split(' ').includes('p-0')

  // Idempotent by construction, with no "closing" ref: every close request — animated or not —
  // is one state flip, and a second request during the exit sets `exiting` to the value it already
  // has, so the timer effect below neither re-runs nor calls `onClose` twice. (A ref here made the
  // callback a ref-carrying value, which the compiler's ref rule refuses to hand to a render-prop
  // child.)
  const requestClose = useCallback(() => setExiting(true), [])

  // Hardware/browser Back closes it, exactly like the scrim and Escape do.
  useBackDismiss(requestClose)
  useScrollLock()

  // Run the parent's unmount after the exit animation. A timer rather than `animationend`: under
  // reduced motion the animation never fires an event, and a dialog that can only be closed by
  // users who allow animation is not a dialog — there the delay is skipped, not waited out.
  useEffect(() => {
    if (!exiting) return
    const id = window.setTimeout(onClose, prefersReducedMotion() ? 0 : DIALOG_EXIT_MS)
    return () => window.clearTimeout(id)
  }, [exiting, onClose])

  // Move focus in, and put it back where it came from on the way out.
  useEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null
    panelRef.current?.focus()
    return () => previouslyFocused?.focus?.()
  }, [])

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      e.stopPropagation()
      requestClose()
      return
    }
    if (e.key !== 'Tab') return
    const panel = panelRef.current
    if (!panel) return
    const focusables = [...panel.querySelectorAll<HTMLElement>(FOCUSABLE)].filter(
      (el) => el.offsetParent !== null || el === document.activeElement,
    )
    if (focusables.length === 0) {
      e.preventDefault()
      panel.focus()
      return
    }
    const first = focusables[0]
    const last = focusables[focusables.length - 1]
    const active = document.activeElement
    if (e.shiftKey && (active === first || active === panel)) {
      e.preventDefault()
      last.focus()
    } else if (!e.shiftKey && active === last) {
      e.preventDefault()
      first.focus()
    }
  }

  return (
    <div
      className="fixed inset-0 z-[var(--z-overlay)] flex items-end justify-center sm:items-center sm:p-4"
      onKeyDown={onKeyDown}
      role="presentation"
    >
      <div
        aria-hidden="true"
        onClick={requestClose}
        className={cn(
          'absolute inset-0 bg-scrim backdrop-blur-sm',
          exiting ? 'scrim-out' : 'scrim-in',
        )}
      />
      <Card
        ref={panelRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={ariaLabel}
        className={cn(
          // Card is border-only since ADR 0077, so the modal layer carries its own elevation —
          // matching Drawer and MobileSheet, which already did.
          'relative w-full p-6 shadow-lg outline-none',
          size === 'lg' ? 'max-w-2xl' : 'max-w-md',
          // Phone: a bottom sheet flush to the edge. Tablet+: a centred card.
          'max-sm:max-h-[92dvh] max-sm:max-w-full max-sm:overflow-y-auto max-sm:rounded-b-none max-sm:rounded-t-sheet',
          exiting ? 'max-sm:sheet-down sm:dialog-out' : 'max-sm:sheet-up sm:dialog-in',
          className,
        )}
      >
        {typeof children === 'function' ? children(requestClose) : children}
        {/* Phone sheet: clear the Android navigation bar (fixed surfaces bypass the body's
            safe-area padding). A spacer, not padding, so the caller's p-4/p-5/p-6 stay exact. */}
        {!ownsEdges ? (
          <div aria-hidden className="shrink-0 sm:hidden" style={{ height: SAFE_AREA_BOTTOM }} />
        ) : null}
      </Card>
    </div>
  )
}
