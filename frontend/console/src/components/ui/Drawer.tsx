import { useEffect, type ReactNode } from 'react'

/**
 * Right-hand side panel (Native Console Web design). The complement of DialogOverlay: a dialog is
 * for one decision that can be answered without looking at anything behind it; a Drawer is for one
 * object with several actions where the list behind should stay visible. Closes on backdrop click
 * or Escape. Compose the body from Drawer.Header-like slots by the caller — this only owns the
 * overlay, the panel chrome, and the entrance.
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
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [onClose])

  return (
    <div className="fixed inset-0 z-50" role="dialog" aria-modal="true" aria-label={ariaLabel}>
      <div className="absolute inset-0 bg-black/30" onClick={onClose} aria-hidden="true" />
      <div className="animate-drawer-in absolute inset-y-0 right-0 flex w-full max-w-[420px] flex-col border-l border-line bg-surface shadow-lg">
        {children}
      </div>
    </div>
  )
}
