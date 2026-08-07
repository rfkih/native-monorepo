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
 */
import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { cn } from '@/lib/cn'

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
  children: React.ReactNode
}) {
  const { t } = useTranslation()
  const panelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    panelRef.current?.focus()
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="fixed inset-0 z-50" role="presentation">
      <button
        type="button"
        aria-label={t('common.close')}
        onClick={onClose}
        className="motion-safe:animate-in motion-safe:fade-in-0 absolute inset-0 cursor-default bg-[rgba(14,17,22,0.42)]"
      />
      <div
        ref={panelRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={ariaLabel}
        className={cn(
          'sheet-up absolute inset-x-0 bottom-0 flex min-h-0 flex-col rounded-t-[26px] bg-surface shadow-lg outline-none',
          height === 'full' ? 'h-[calc(100dvh-110px)]' : 'max-h-[85dvh]',
        )}
        style={{ paddingBottom: 'var(--safe-area-inset-bottom, 0px)' }}
      >
        <div className="flex shrink-0 justify-center pt-2.5 pb-1" aria-hidden>
          <div className="h-1 w-[38px] rounded-full bg-line" />
        </div>
        {children}
      </div>
    </div>
  )
}
