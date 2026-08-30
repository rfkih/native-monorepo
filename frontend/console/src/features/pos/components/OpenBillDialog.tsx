/**
 * OpenBillDialog.tsx — extracted VERBATIM from Pos.tsx (redesign P2, mechanical move only).
 * Markup and behavior unchanged; closures became props where needed.
 */
import { useState,
} from 'react'
import { useTranslation } from 'react-i18next'
import {
  X,
} from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { isOutletNotAssigned } from '@/lib/api'
import type { CompanySession } from '@/lib/session'
import { cn } from '@/lib/cn'
import { useOpenBill,
} from '../billsApi'
import type { } from '../lib/categories'
import type { } from '@/features/loyalty/api'


// ---------------------------------------------------------------------------
// OpenBillDialog — create a new bill (was inner of BillsTray)
// ---------------------------------------------------------------------------

export function OpenBillDialog({
  session,
  tables,
  onCreated,
  onClose,
}: {
  session: CompanySession
  tables: import('../api').TableResponse[]
  onCreated: (billId: string) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  useBackDismiss(onClose)
  const openBill = useOpenBill(session)
  const [selectedTableId, setSelectedTableId] = useState<string | null>(null)
  const [guestLabel, setGuestLabel] = useState('')
  const [touched, setTouched] = useState(false)

  const guestLabelError = touched && guestLabel.trim() === '' ? t('bills.guestLabelRequired') : null
  const canSubmit = guestLabel.trim().length > 0 && !openBill.isPending

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setTouched(true)
    if (!canSubmit) return
    openBill.mutate(
      { tableId: selectedTableId, guestLabel: guestLabel.trim() },
      {
        onSuccess: (res) => {
          if (res?.id) onCreated(res.id)
        },
},
    )
  }

  const activeTables = tables.filter((tbl) => tbl.active)

  return (
    <div
      className="fixed inset-0 z-[60] grid place-items-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('bills.openBillTitle')}
    >
      <div className="max-h-full w-full max-w-sm overflow-y-auto overscroll-contain rounded-[20px] border border-line bg-surface shadow-xl">
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <h3 className="font-display text-lg font-semibold text-ink">{t('bills.openBillTitle')}</h3>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('common.cancel')}
            className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <X className="size-4" />
          </button>
        </div>
        <form onSubmit={handleSubmit} noValidate>
          <div className="space-y-5 px-5 py-4">
            <div>
              <label htmlFor="bill-guest-label" className="mb-1.5 block text-sm font-medium text-ink">
                {t('bills.guestLabel')}
              </label>
              <input
                id="bill-guest-label"
                type="text"
                autoFocus
                maxLength={128}
                value={guestLabel}
                onChange={(e) => setGuestLabel(e.target.value)}
                onBlur={() => setTouched(true)}
                placeholder={t('bills.guestLabelPlaceholder')}
                aria-describedby={guestLabelError ? 'bill-guest-error' : undefined}
                className={cn(
                  'w-full rounded-xl border bg-surface px-3 py-2 text-sm text-ink placeholder:text-ink-3/50 transition-colors',
                  'focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/10',
                  guestLabelError ? 'border-loss' : 'border-line',
                )}
              />
              {guestLabelError ? (
                <p id="bill-guest-error" className="mt-1 text-xs text-loss" role="alert">
                  {guestLabelError}
                </p>
              ) : null}
            </div>
            <div>
              <p className="mb-2 text-sm font-medium text-ink">{t('bills.selectTable')}</p>
              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  onClick={() => setSelectedTableId(null)}
                  aria-pressed={selectedTableId === null}
                  className={cn(
                    'w-[68px] rounded-xl border-[1.5px] py-2 text-center transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                    selectedTableId === null
                      ? 'border-emerald bg-emerald text-on-emerald'
                      : 'border-line bg-surface hover:bg-hover',
                  )}
                >
                  <div className={cn('text-sm font-bold', selectedTableId === null ? 'text-on-emerald' : 'text-ink')}>—</div>
                  <div className={cn('text-[10px]', selectedTableId === null ? 'text-on-emerald/80' : 'text-ink-3')}>
                    {t('bills.noTable')}
                  </div>
                </button>
                {activeTables.map((tbl) => {
                  const selected = selectedTableId === tbl.tableId
                  return (
                    <button
                      key={tbl.tableId}
                      type="button"
                      onClick={() => setSelectedTableId(tbl.tableId)}
                      aria-pressed={selected}
                      aria-label={t('pos.table.selectLabel', { label: tbl.label })}
                      className={cn(
                        'w-[68px] rounded-xl border-[1.5px] py-2 text-center transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                        selected
                          ? 'border-emerald bg-emerald text-on-emerald'
                          : 'border-line bg-surface hover:bg-hover',
                      )}
                    >
                      <div className={cn('text-sm font-bold', selected ? 'text-on-emerald' : 'text-ink')}>{tbl.label}</div>
                      <div className={cn('text-[10px]', selected ? 'text-on-emerald/80' : 'text-ink-3')}>
                        {t('pos.table.capacity', { n: tbl.capacity })}
                      </div>
                    </button>
                  )
                })}
              </div>
            </div>
            {openBill.isError ? (
              <p className="text-xs text-loss" role="alert">
                {isOutletNotAssigned(openBill.error)
                  ? t('pos.payment.outletNotAssigned')
                  : (openBill.error as Error).message}
              </p>
            ) : null}
          </div>
          <div className="flex gap-2 border-t border-line px-5 py-4">
            <Button type="button" variant="outline" className="flex-1" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" className="flex-1" disabled={!canSubmit}>
              {openBill.isPending ? <Spinner /> : t('bills.startBill')}
            </Button>
          </div>
        </form>
      </div>
    </div>
  )
}
