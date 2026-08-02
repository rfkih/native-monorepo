/**
 * CancelConfirmDialog.tsx — extracted VERBATIM from BillDetail.tsx (redesign P2, mechanical move only).
 */
import { useTranslation } from 'react-i18next'
import {
  AlertTriangle,
} from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import type { } from '@/lib/session'
import type { BillResponse,
} from '../billsApi'
import type { } from '../lib/categories'


// ---------------------------------------------------------------------------
// CancelConfirmDialog
// ---------------------------------------------------------------------------

export function CancelConfirmDialog({
  bill,
  isCancelling,
  error,
  onConfirm,
  onClose,
}: {
  bill: BillResponse
  isCancelling: boolean
  error: string | null
  onConfirm: () => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  return (
    <div
      className="fixed inset-0 z-[70] grid place-items-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('bills.cancelBillTitle')}
    >
      <Card className="w-full max-w-sm overflow-hidden">
        <div className="flex items-start gap-3 border-b border-line px-5 py-4">
          <AlertTriangle className="mt-0.5 size-5 shrink-0 text-loss" aria-hidden="true" />
          <div>
            <h3 className="font-display text-lg font-semibold text-ink">
              {t('bills.cancelBillTitle')}
            </h3>
            <p className="mt-1 text-sm text-ink-3">
              {t('bills.cancelBillBody', { label: bill.guestLabel })}
            </p>
          </div>
        </div>
        {error ? (
          <p className="px-5 pt-3 text-xs text-loss" role="alert">
            {error}
          </p>
        ) : null}
        <div className="flex gap-2 px-5 py-4">
          <Button variant="outline" className="flex-1" onClick={onClose} disabled={isCancelling}>
            {t('common.cancel')}
          </Button>
          <Button
            className="flex-1 bg-loss hover:bg-loss/90 focus-visible:outline-loss"
            onClick={onConfirm}
            disabled={isCancelling}
          >
            {isCancelling ? <Spinner /> : t('bills.cancelBillConfirm')}
          </Button>
        </div>
      </Card>
    </div>
  )
}
