import { useEffect, type ReactNode } from 'react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { useScrollLock } from '@/components/mobile/useScrollLock'
import { SAFE_AREA_BOTTOM, SAFE_AREA_TOP } from '@/lib/safeArea'

/**
 * Right-hand side panel (Native Console Web design). The complement of DialogOverlay: a dialog is
 * for one decision that can be answered without looking at anything behind it; a Drawer is for one
 * object with several actions where the list behind should stay visible. Closes on backdrop click,
 * Escape, or the phone/browser Back button (useBackDismiss). Compose the body from
 * Drawer.Header-like slots by the caller — this only owns the overlay, the panel chrome, and the
 * entrance.
 */
export function Drawer({
  children,
  onClose,
  ariaLabel,
}: {
  children: ReactNode
  onClose: () => void
  ariaLabel?: string
}) {
  useBackDismiss(onClose)
  useScrollLock()
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [onClose])

  return (
    <div className="fixed inset-0 z-50" role="dialog" aria-modal="true" aria-label={ariaLabel}>
      <div className="absolute inset-0 bg-scrim" onClick={onClose} aria-hidden="true" />
      {/* inset-y-0 runs under BOTH Android bars; the panel pads them so its header is not under
          the clock and its footer not under the home/back buttons (lib/safeArea). */}
      <div
        className="animate-drawer-in absolute inset-y-0 right-0 flex w-full max-w-[420px] flex-col border-l border-line bg-surface shadow-lg"
        style={{ paddingTop: SAFE_AREA_TOP, paddingBottom: SAFE_AREA_BOTTOM }}
      >
        {children}
      </div>
    </div>
  )
}
