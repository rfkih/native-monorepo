/**
 * LeaveConfirmDialog — the back-guard's confirmation surface (frame per CancelConfirmDialog).
 *
 * z-[100]: must clear every feature overlay (the current ceiling is z-[85]) because the guard is
 * the last line of defence — it can appear above an overlay the guard couldn't close (sequence 15).
 * Deliberately does NOT use useBackDismiss: the guard's own popstate logic already treats a Back
 * press while this dialog is open as Cancel.
 */
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { LogOut } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { useScrollLock } from './useScrollLock'
import type { BackGuardDialogKind } from './useBackGuard'

export function LeaveConfirmDialog({
  kind,
  onConfirm,
  onCancel,
}: {
  kind: BackGuardDialogKind
  onConfirm: () => void
  onCancel: () => void
}) {
  const { t } = useTranslation()
  useScrollLock()

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') onCancel()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [onCancel])

  const title = t(kind === 'exit' ? 'backGuard.exitTitle' : 'backGuard.leaveTitle')
  return (
    <div
      className="fixed inset-0 z-[100] grid place-items-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={title}
      onClick={(e) => {
        if (e.target === e.currentTarget) onCancel()
      }}
    >
      <Card className="w-full max-w-sm overflow-hidden">
        <div className="flex items-start gap-3 border-b border-line px-5 py-4">
          <LogOut className="mt-0.5 size-5 shrink-0 text-ink-3" aria-hidden="true" />
          <div>
            <h3 className="font-display text-lg font-semibold text-ink">{title}</h3>
            <p className="mt-1 text-sm text-ink-3">
              {t(kind === 'exit' ? 'backGuard.exitBody' : 'backGuard.leaveBody')}
            </p>
          </div>
        </div>
        <div className="flex gap-2 px-5 py-4">
          <Button variant="outline" className="flex-1" onClick={onCancel}>
            {t('common.cancel')}
          </Button>
          <Button className="flex-1" onClick={onConfirm}>
            {t(kind === 'exit' ? 'backGuard.exitConfirm' : 'backGuard.leaveConfirm')}
          </Button>
        </div>
      </Card>
    </div>
  )
}
