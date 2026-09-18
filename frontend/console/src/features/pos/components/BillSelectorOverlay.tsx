/**
 * BillSelectorOverlay — the ORDER SWITCHER (redesign P4, grown from the phone-only bill list).
 *
 * Opened from the ticket dock's destination pill on every viewport: a pinned WALK-IN row (the
 * local cart), one row per open bill, and shortcuts to the table floor and the parked tray.
 * The switcher is how the cashier answers "which order am I ringing?" — the old implicit
 * cart-vs-bill mode as explicit navigation.
 *
 * ADR 0086 — each open-bill row also carries a CANCEL action, because this is where an owner lands
 * from the home's "Bill terbuka" door and from the close sheet's "Lihat tagihan": an accidental
 * bill is discarded here without first picking it up and expanding the deck. Same policy as the
 * deck's cancel row (lib/billPermissions.ts — owner/manager for a bill with lines, anyone for an
 * empty one, nobody once a split check is paid); the server is the real boundary. The confirm is
 * the shared CancelConfirmDialog, above this overlay in the till's own z-stack.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ClipboardList, Plus, ShoppingBag, Table2, X } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { useScrollLock } from '@/components/mobile/useScrollLock'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import { useCancelBill, type BillSummaryResponse } from '../billsApi'
import { canCancelBill } from '../lib/billPermissions'
import { billProblemMessage } from '../lib/billProblem'
import { CancelConfirmDialog } from './CancelConfirmDialog'

export function BillSelectorOverlay({
  session,
  bills,
  activeBillId,
  locale,
  walkInCount,
  walkInTotalMinor,
  currency,
  canVoid,
  onWalkIn,
  onOpenFloor,
  onOpenParked,
  onSelect,
  onNewBill,
  onCancelled,
  onClose,
}: {
  session: CompanySession
  bills: BillSummaryResponse[]
  activeBillId: string | null
  locale: string
  /** The local walk-in cart, pinned as the first destination (redesign P4). */
  walkInCount: number
  walkInTotalMinor: number
  currency: string
  /** Owner/manager (merged roles, so an elevated device lights it) — may cancel a bill with lines. */
  canVoid: boolean
  onWalkIn: () => void
  /** Null while offline (the floor/parked need a connection — ADR 0028). */
  onOpenFloor: (() => void) | null
  onOpenParked: (() => void) | null
  onSelect: (billId: string) => void
  onNewBill: () => void
  /** A row was cancelled here — the host drops it if it was the active bill. */
  onCancelled?: (billId: string) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  useBackDismiss(onClose)
  useScrollLock()
  // The tap-opened confirm parks its own Back entry ABOVE this one (the registry hands popstate to
  // the topmost overlay), exactly as the deck's confirm does in BillDetail — no gating here; that
  // rule is for a confirm a Back press itself opened.
  const [cancelTarget, setCancelTarget] = useState<BillSummaryResponse | null>(null)
  const cancelBill = useCancelBill(session)
  const walkInActive = activeBillId === null
  const cancellable = (bill: BillSummaryResponse) =>
    canCancelBill(canVoid, bill.lineCount, (bill.paidLineCount ?? 0) > 0)
  // The lockdown explains itself once, under the list — not per row (touch has no tooltips).
  const needsManagerHint = !canVoid && bills.some((b) => b.lineCount > 0)
  const closeConfirm = () => {
    cancelBill.reset()
    setCancelTarget(null)
  }
  return (
    <div
      className="fixed inset-0 z-50 flex flex-col bg-paper sm:bg-scrim sm:p-6 sm:backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('posShell.switcherTitle')}
    >
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden bg-paper sm:mx-auto sm:my-auto sm:max-h-[80vh] sm:w-full sm:max-w-md sm:rounded-2xl sm:border sm:border-line sm:shadow-lg">
        <div className="flex items-center justify-between border-b border-line bg-surface px-4 py-3">
          <h2 className="font-display text-lg font-semibold text-ink">{t('posShell.switcherTitle')}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('common.close')}
            className="grid size-9 place-items-center rounded-xl text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <X className="size-4" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-4">
          {/* Pinned walk-in destination */}
          <button
            type="button"
            data-testid="switcher-walkin"
            onClick={onWalkIn}
            className={cn(
              'mb-2 w-full rounded-xl border px-4 py-3 text-left transition-all focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
              walkInActive
                ? 'border-emerald bg-emerald-tint'
                : 'border-line bg-surface hover:border-emerald-line hover:bg-emerald-tint/30',
            )}
          >
            <div className="flex items-center justify-between">
              <span className="flex items-center gap-2 font-semibold text-ink">
                <ShoppingBag className="size-4 text-emerald-2" aria-hidden="true" />
                {t('posShell.walkIn')}
              </span>
              <span className="tnum font-mono text-sm font-semibold text-ink">
                {walkInCount > 0 ? formatMoney(walkInTotalMinor, currency, locale) : '—'}
              </span>
            </div>
            <div className="mt-1 text-xs text-ink-3">{t('bills.lineCount', { n: walkInCount })}</div>
          </button>

          {bills.length === 0 ? (
            <p className="py-8 text-center text-sm text-ink-3">{t('bills.noBills')}</p>
          ) : (
            <div className="space-y-2">
              {bills.map((bill) => (
                // A button cannot nest a button — the row is a div: the select target plus, when
                // the policy allows it, a small cancel action on the meta line.
                <div
                  key={bill.id}
                  className={cn(
                    'rounded-xl border transition-all',
                    bill.id === activeBillId
                      ? 'border-emerald bg-emerald-tint'
                      : 'border-line bg-surface hover:border-emerald-line hover:bg-emerald-tint/30',
                  )}
                >
                  <button
                    type="button"
                    onClick={() => onSelect(bill.id)}
                    className="w-full rounded-xl px-4 pt-3 pb-1 text-left focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
                  >
                    <div className="flex items-center justify-between gap-3">
                      <span className="min-w-0 truncate font-semibold text-ink">{bill.guestLabel}</span>
                      <span className="tnum shrink-0 font-mono text-sm font-semibold text-ink">
                        {formatMoney(
                          bill.runningTotalMinor,
                          // An empty bill carries the "XXX" placeholder until its first line.
                          bill.currency === 'XXX' ? currency : bill.currency,
                          locale,
                        )}
                      </span>
                    </div>
                  </button>
                  <div className="flex items-center justify-between gap-3 px-4 pb-2">
                    <span className="text-xs text-ink-3">
                      {t('bills.lineCount', { n: bill.lineCount })}
                    </span>
                    {cancellable(bill) ? (
                      <button
                        type="button"
                        data-testid={`switcher-cancel-${bill.id}`}
                        aria-label={t('bills.cancelBillAria', { label: bill.guestLabel })}
                        onClick={() => setCancelTarget(bill)}
                        className="-mr-2 min-h-9 rounded-lg px-2 text-xs font-semibold text-loss-ink transition-colors hover:bg-tint-loss focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
                      >
                        {t('bills.cancelBill')}
                      </button>
                    ) : null}
                  </div>
                </div>
              ))}
              {needsManagerHint ? (
                <p className="px-1 pt-1 text-xs leading-relaxed text-ink-3">
                  {t('bills.cancelNeedsManager')}
                </p>
              ) : null}
            </div>
          )}
        </div>

        <div className="space-y-2 border-t border-line p-4">
          {/* Floor / parked shortcuts (hidden offline — both need a connection, ADR 0028) */}
          {onOpenFloor || onOpenParked ? (
            <div className="flex gap-2">
              {onOpenFloor ? (
                <button
                  type="button"
                  onClick={onOpenFloor}
                  className="flex flex-1 items-center justify-center gap-2 rounded-xl border border-line bg-surface py-2.5 text-sm font-semibold text-ink-2 hover:border-emerald-line hover:bg-emerald-tint/40 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
                >
                  <Table2 className="size-4" aria-hidden="true" />
                  {t('posShell.switcherFloor')}
                </button>
              ) : null}
              {onOpenParked ? (
                <button
                  type="button"
                  onClick={onOpenParked}
                  className="flex flex-1 items-center justify-center gap-2 rounded-xl border border-line bg-surface py-2.5 text-sm font-semibold text-ink-2 hover:border-emerald-line hover:bg-emerald-tint/40 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
                >
                  <ClipboardList className="size-4" aria-hidden="true" />
                  {t('posShell.switcherParked')}
                </button>
              ) : null}
            </div>
          ) : null}
          <button
            type="button"
            onClick={onNewBill}
            className="flex w-full items-center justify-center gap-2 rounded-xl border-[1.5px] border-dashed border-emerald-line bg-surface py-3 text-base font-semibold text-emerald-2 hover:bg-emerald-tint focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <Plus className="size-5" aria-hidden="true" />
            {t('bills.newBill')}
          </button>
        </div>
      </div>
      {cancelTarget ? (
        <CancelConfirmDialog
          guestLabel={cancelTarget.guestLabel}
          isCancelling={cancelBill.isPending}
          error={cancelBill.isError ? billProblemMessage(t, cancelBill.error) : null}
          onConfirm={() => {
            const id = cancelTarget.id
            cancelBill.mutate(id, {
              onSuccess: () => {
                onCancelled?.(id)
                closeConfirm()
              },
            })
          }}
          onClose={closeConfirm}
        />
      ) : null}
    </div>
  )
}
