/**
 * MobileSheet — the phone bottom sheet (Native Console Android design): dimmed backdrop,
 * rounded-top panel with a drag-handle pill, slide-up entrance. Focus contract mirrors
 * TillMenuSheet (SheetOverlay rules): initial focus on the panel, Escape closes, backdrop
 * click closes. Stateless — content and close handling come from the caller.
 *
 * height 'auto' caps at 85dvh; 'full' pins to calc(100dvh-110px) so callers can build a
 * scrollable body + sticky footer (the claim-decision sheet). Panel is `flex flex-col
 * min-h-0` for exactly that. Carries its own safe-area-inset-bottom (fixed elements
 * bypass the body padding — index.css).
 *
 * It LEAVES the way it arrived (motion language, index.css): every close — scrim, Escape, Back —
 * plays the scrim fade + slide-down first and only then tells the parent to unmount, exactly as
 * `DialogOverlay` does. The parent still owns mounting; `onClose` is simply called a beat later.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { cn } from '@/lib/cn'
import { exitDelayMs } from '@/lib/motion'
import { useBackDismiss } from './useBackDismiss'
import { useScrollLock } from './useScrollLock'

export function MobileSheet({
  onClose,
  ariaLabel,
  height = 'auto',
  children,
}: {
  onClose: () => void
  /** Already-translated dialog label. */
  ariaLabel: string
  height?: 'auto' | 'full'
  /** Plain content, or a render function handed the sheet's own close — so a Done/Cancel button
   *  drawn INSIDE the sheet plays the same exit as the scrim, Escape and Back (as in Dialog). */
  children: React.ReactNode | ((requestClose: () => void) => React.ReactNode)
}) {
  const { t } = useTranslation()
  const panelRef = useRef<HTMLDivElement>(null)
  const [exiting, setExiting] = useState(false)
  // Idempotent: a second close request during the exit is a no-op state flip.
  const requestClose = useCallback(() => setExiting(true), [])
  useEffect(() => {
    if (!exiting) return
    const id = window.setTimeout(onClose, exitDelayMs())
    return () => window.clearTimeout(id)
  }, [exiting, onClose])

  // Phone/browser BACK closes the sheet instead of navigating off the page (Android hardware back
  // in the native shell, the browser back gesture in a PWA). Mirrors the Escape handler below; the
  // caller's reopen affordance (e.g. the POS dock's expand chevron) is unaffected.
  useBackDismiss(requestClose)
  // Freeze the page behind the sheet — touch flicks otherwise scroll-chain through the backdrop.
  useScrollLock()

  useEffect(() => {
    panelRef.current?.focus()
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') requestClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [requestClose])

  return (
    <div className="fixed inset-0 z-50" role="presentation">
      <button
        type="button"
        aria-label={t('common.close')}
        onClick={requestClose}
        className={cn('absolute inset-0 cursor-default bg-scrim', exiting ? 'scrim-out' : 'scrim-in')}
      />
      <div
        ref={panelRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={ariaLabel}
        className={cn(
          'absolute inset-x-0 bottom-0 flex min-h-0 flex-col rounded-t-sheet bg-surface shadow-lg outline-none',
          exiting ? 'sheet-down' : 'sheet-up',
          height === 'full' ? 'h-[calc(100dvh-110px)]' : 'max-h-[85dvh]',
        )}
        style={{ paddingBottom: 'var(--safe-area-inset-bottom, 0px)' }}
      >
        <div className="flex shrink-0 justify-center pt-2.5 pb-1" aria-hidden>
          <div className="h-1 w-[38px] rounded-full bg-line" />
        </div>
        {typeof children === 'function' ? children(requestClose) : children}
      </div>
    </div>
  )
}
