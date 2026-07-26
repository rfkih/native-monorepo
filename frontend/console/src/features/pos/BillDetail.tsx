/**
 * BillDetail — full-screen "bill mode" for one OPEN guest bill.
 *
 * Layout mirrors the POS (menu grid on the left / bill rail on the right), but:
 *  - The rail shows the bill's persisted lines (from the server), not a local cart.
 *  - "Add items" taps feed directly into POST /api/v1/bills/{id}/lines (not a local cart).
 *  - Individual lines are removable via DELETE /api/v1/bills/{id}/lines/{lineId}.
 *  - "Pay" opens BillPaymentModal wired to POST /api/v1/bills/{id}/pay.
 *  - "Split" toggle: selects a subset of unpaid lines, pays them as a check; already-paid
 *    lines show a "Paid" badge and cannot be selected.
 *  - "Kitchen ticket" (KOT) button: prints a price-free kitchen ticket.
 *  - "Cancel bill" voids the OPEN bill with no sale.
 *
 * Money rule (rule 8): all amounts are integer minor units, rendered via formatMoney().
 * Strings rule (rule 9): every user-facing string is an i18n key.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  ArrowLeft,
  Trash2,
  Table2,
  ReceiptText,
  AlertTriangle,
  X,
  ChefHat,
  SplitSquareHorizontal,
} from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import {
  useMenu,
  useCategories,
  type MenuItem,
  type CategoryResponse,
  type PriceBreakdownResponse,
} from './api'
import { ModifierModal } from './ModifierModal'
import { BillPaymentModal } from './BillPaymentModal'
import { BillReceiptView } from './BillReceiptView'
import { KotView } from './KotView'
import {
  useBill,
  useAppendLines,
  useRemoveLine,
  useCancelBill,
  type BillResponse,
  type BillLineResponse,
} from './billsApi'
import { Segmented } from '@/components/ui/Segmented'

interface Props {
  session: CompanySession
  locale: string
  billId: string
  /** Label of the table the bill is on, or null */
  tableLabel: string | null
  onBack: () => void
  onPaid: () => void
}

export function BillDetail({ session, locale, billId, tableLabel, onBack, onPaid }: Props) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const billQuery = useBill(session, billId)
  const menuQuery = useMenu(session)
  const categoriesQuery = useCategories(session)
  const appendLines = useAppendLines(session)
  const removeLine = useRemoveLine(session)
  const cancelBill = useCancelBill(session)

  const bill = billQuery.data
  const items = menuQuery.data ?? []
  const categories = (categoriesQuery.data ?? []).filter((c) => c.active)

  const [modifierItem, setModifierItem] = useState<MenuItem | null>(null)
  const [showPayModal, setShowPayModal] = useState(false)
  const [showCancelConfirm, setShowCancelConfirm] = useState(false)
  const [billOpen, setBillOpen] = useState(false) // mobile: bill rail drawer

  // ─── Split mode ───────────────────────────────────────────────────────────
  const [splitMode, setSplitMode] = useState(false)
  const [selectedLineIds, setSelectedLineIds] = useState<Set<string>>(new Set())

  // ─── Receipt state (shown after a check is paid) ─────────────────────────
  interface CheckResult {
    paidLines: BillLineResponse[]
    checkTotalMinor: number
    tenderType: 'CASH' | 'QRIS' | 'CARD'
    tenderedMinor?: number
    changeMinor?: number
  }
  const [checkResult, setCheckResult] = useState<CheckResult | null>(null)

  // ─── KOT state ────────────────────────────────────────────────────────────
  const [showKot, setShowKot] = useState(false)

  // Category tab bar
  const [activeCategoryId, setActiveCategoryId] = useState<string | null>(null)
  const orderedCategories = deriveCategories(items, categories)
  const resolvedCategoryId: string = activeCategoryId ?? orderedCategories[0]?.id ?? ''
  const categoryOptions = orderedCategories.map((c) => ({ value: c.id, label: c.name }))

  const visibleItems = items.filter((item) => {
    if (orderedCategories.length === 0) return true
    const cat = orderedCategories.find((c) => c.id === resolvedCategoryId)
    if (!cat) return true
    if (item.categoryId) return item.categoryId === resolvedCategoryId
    return item.category === cat.legacyKey
  })

  const currency = bill?.currency ?? items[0]?.currency ?? session.baseCurrency
  const lineCount = bill?.lines.reduce((s, l) => s + l.qty, 0) ?? 0

  // Remaining (unpaid) lines for the bill rail
  const unpaidLines = bill?.lines.filter((l) => !l.paid) ?? []
  const unpaidTotal = unpaidLines.reduce((s, l) => s + l.lineTotalMinor, 0)
  const grandTotal =
    bill?.breakdown?.grandTotalMinor ??
    bill?.lines.reduce((s, l) => s + l.lineTotalMinor, 0) ??
    0

  // Split: total of selected lines
  const selectedLines = unpaidLines.filter((l) => selectedLineIds.has(l.id))
  const selectedTotal = selectedLines.reduce((s, l) => s + l.lineTotalMinor, 0)

  // Adding items: tap → ModifierModal (if modifiers) → appendLines
  function handleItemTap(item: MenuItem) {
    if (!item.available || (item.stockQuantity != null && item.stockQuantity <= 0)) return
    if (!bill || bill.status !== 'OPEN') return
    if (item.modifierGroups.length > 0) {
      setModifierItem(item)
      return
    }
    appendLines.mutate({
      billId,
      lines: [{ menuItemId: item.id, qty: 1, selectedOptionIds: [] }],
    })
  }

  function handleModifierConfirm(selectedOptionIds: string[]) {
    if (!modifierItem || !bill) return
    appendLines.mutate({
      billId,
      lines: [{ menuItemId: modifierItem.id, qty: 1, selectedOptionIds }],
    })
    setModifierItem(null)
  }

  function handleRemoveLine(lineId: string) {
    removeLine.mutate({ billId, lineId })
  }

  function handleCancel() {
    cancelBill.mutate(billId, {
      onSuccess: () => {
        onBack()
      },
    })
  }

  // ─── Split mode helpers ───────────────────────────────────────────────────

  function toggleSplitMode() {
    setSplitMode((v) => !v)
    setSelectedLineIds(new Set())
  }

  function toggleLineSelection(lineId: string) {
    setSelectedLineIds((prev) => {
      const next = new Set(prev)
      if (next.has(lineId)) {
        next.delete(lineId)
      } else {
        next.add(lineId)
      }
      return next
    })
  }

  /** Generates a fresh idempotency key per check-pay attempt. */
  function freshKey(): string {
    return freshIdempotencyKey()
  }

  // We use a richer callback from the payment modal that includes tender details.
  // The BillPaymentModal calls onSuccess() without arguments; we capture payment
  // data via a ref-like approach using state set before calling onSuccess.

  // Because BillPaymentModal.onSuccess doesn't carry tender data back, we
  // track what the user is about to pay in state, then capture it on success.
  interface PendingPayInfo {
    lineIds?: string[]
    checkTotalMinor: number
    idempotencyKey: string
    paidLineObjects: BillLineResponse[]
  }
  const [pendingPay, setPendingPay] = useState<PendingPayInfo | null>(null)

  function openPayModal() {
    if (splitMode) {
      const key = freshKey()
      setPendingPay({
        lineIds: selectedLines.map((l) => l.id),
        checkTotalMinor: selectedTotal,
        idempotencyKey: key,
        paidLineObjects: selectedLines,
      })
    } else {
      setPendingPay({
        lineIds: undefined,
        checkTotalMinor: unpaidTotal,
        idempotencyKey: freshKey(),
        paidLineObjects: unpaidLines,
      })
    }
    setShowPayModal(true)
  }

  function handlePaySuccess() {
    setShowPayModal(false)
    // Refresh bill data from server so paid flags update
    void qc.invalidateQueries({ queryKey: ['bill', session.companyId, billId] })

    if (pendingPay) {
      setCheckResult({
        paidLines: pendingPay.paidLineObjects,
        checkTotalMinor: pendingPay.checkTotalMinor,
        // We don't have tender type here; BillPaymentModal doesn't surface it via onSuccess.
        // Default to CASH — receipt will show without tendered/change if QRIS/CARD.
        tenderType: 'CASH',
      })
    }
    setPendingPay(null)
    setSelectedLineIds(new Set())
    setSplitMode(false)
  }

  function handleReceiptClose() {
    setCheckResult(null)
    // If all lines are paid, the bill should now be PAID — navigate back via onPaid.
    const latestBill = billQuery.data
    if (!latestBill || latestBill.status === 'PAID') {
      onPaid()
    }
  }

  if (billQuery.isLoading || !bill) {
    return (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-paper">
        <Spinner />
      </div>
    )
  }

  if (bill.status !== 'OPEN') {
    // Bill is already PAID or CANCELLED — just show a message and let them go back
    return (
      <div className="fixed inset-0 z-50 flex flex-col bg-paper">
        <div className="flex items-center gap-3 border-b border-line bg-surface px-4 py-3">
          <button
            type="button"
            onClick={onBack}
            aria-label={t('bills.backToBills')}
            className="grid size-9 place-items-center rounded-xl border border-line text-ink-3 hover:bg-hover"
          >
            <ArrowLeft className="size-4.5" />
          </button>
          <span className="font-display text-lg font-semibold text-ink">{bill.guestLabel}</span>
        </div>
        <div className="grid flex-1 place-items-center p-8 text-center">
          <div>
            <ReceiptText className="mx-auto mb-3 size-10 text-ink-3/40" />
            <p className="font-semibold text-ink">{t('bills.billClosed')}</p>
            <p className="mt-1 text-sm text-ink-3">
              {bill.status === 'PAID' ? t('bills.statusPaid') : t('bills.statusCancelled')}
            </p>
            <Button className="mt-5" onClick={onBack}>
              {t('bills.backToBills')}
            </Button>
          </div>
        </div>
      </div>
    )
  }

  const allLinesPaid = bill.lines.length > 0 && bill.lines.every((l) => l.paid)

  return (
    <div className="fixed inset-0 z-50 flex flex-col overflow-hidden bg-paper">
      {/* Bill mode header — always clearly distinct from the normal POS cart */}
      <header className="sticky top-0 z-10 flex flex-wrap items-center gap-3 border-b border-line bg-surface px-4 py-3 sm:px-5">
        <button
          type="button"
          onClick={onBack}
          aria-label={t('bills.backToBills')}
          className="grid size-[38px] shrink-0 place-items-center rounded-xl border border-line text-ink-3 transition-colors hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
        >
          <ArrowLeft className="size-[18px]" />
        </button>

        {/* Bill mode indicator — makes it obvious this is NOT the immediate cart */}
        <div className="flex min-w-0 items-center gap-2">
          <Badge tone="amber" className="shrink-0 text-[11px]">
            {t('bills.billMode')}
          </Badge>
          <span className="truncate font-display text-[17px] font-bold leading-tight tracking-[-0.01em] text-ink">
            {bill.guestLabel}
          </span>
          {tableLabel ? (
            <span className="flex shrink-0 items-center gap-1 text-sm text-ink-3">
              <Table2 className="size-3.5" aria-hidden="true" />
              {tableLabel}
            </span>
          ) : null}
        </div>

        <div className="flex-1" />

        {/* Kitchen ticket button */}
        <button
          type="button"
          onClick={() => setShowKot(true)}
          aria-label={t('kot.title')}
          title={t('kot.title')}
          className="inline-flex shrink-0 items-center gap-1.5 rounded-xl border border-line bg-surface px-3 py-2 text-sm font-semibold text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          disabled={lineCount === 0}
        >
          <ChefHat className="size-4" aria-hidden="true" />
          <span className="hidden sm:inline">{t('kot.title')}</span>
        </button>

        {/* Category tabs — inline from sm up */}
        {categoryOptions.length > 1 ? (
          <div className="order-last w-full overflow-x-auto sm:order-none sm:w-auto sm:overflow-visible">
            <Segmented
              options={categoryOptions}
              value={resolvedCategoryId}
              onChange={(v) => setActiveCategoryId(v)}
              ariaLabel={t('pos.categories')}
            />
          </div>
        ) : null}
      </header>

      {/* Body */}
      <div className="relative flex min-h-0 flex-1 overflow-hidden">
        {/* Menu side */}
        <div className="min-h-0 flex-1 overflow-y-auto px-4 py-5 pb-24 sm:px-5 lg:pb-6">
          {menuQuery.isLoading ? (
            <div className="grid place-items-center py-24 text-brand-500">
              <Spinner />
            </div>
          ) : items.length === 0 ? (
            <p className="py-10 text-center text-sm text-ink-3">{t('pos.emptyMenu')}</p>
          ) : (
            <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5">
              {visibleItems.map((item) => (
                <BillItemCard
                  key={item.id}
                  item={item}
                  locale={locale}
                  isAdding={appendLines.isPending}
                  onAdd={() => handleItemTap(item)}
                />
              ))}
            </div>
          )}

          {appendLines.isError ? (
            <p className="mt-4 text-center text-xs text-loss" role="alert">
              {(appendLines.error as Error).message}
            </p>
          ) : null}
        </div>

        {/* Bill rail — inline on lg+; slide-up drawer below lg */}
        <aside
          className={cn(
            'fixed inset-0 z-40 flex min-h-0 flex-col bg-surface shadow-lg transition-transform duration-300 ease-out',
            billOpen ? 'translate-y-0' : 'translate-y-full',
            'lg:static lg:z-auto lg:w-[380px] lg:shrink-0 lg:translate-y-0 lg:border-l lg:border-line lg:shadow-none lg:transition-none xl:w-[420px]',
          )}
        >
          {/* Rail header */}
          <div className="flex items-center justify-between border-b border-line px-5 py-3.5">
            <div className="flex items-center gap-2">
              <h2 className="font-display text-lg font-semibold text-ink">{t('bills.billLines')}</h2>
              {lineCount > 0 ? <Badge tone="emerald">{lineCount}</Badge> : null}
            </div>
            <div className="flex items-center gap-2">
              {/* Split mode toggle */}
              {unpaidLines.length > 1 ? (
                <button
                  type="button"
                  onClick={toggleSplitMode}
                  aria-pressed={splitMode}
                  aria-label={t('bills.splitToggle')}
                  title={t('bills.splitToggle')}
                  className={cn(
                    'inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
                    splitMode
                      ? 'border-brand-500 bg-brand-50 text-brand-700'
                      : 'border-line bg-surface text-ink-2 hover:bg-hover',
                  )}
                >
                  <SplitSquareHorizontal className="size-3.5" aria-hidden="true" />
                  {t('bills.split')}
                </button>
              ) : null}
              <button
                type="button"
                onClick={() => setBillOpen(false)}
                aria-label={t('common.close')}
                className="grid size-8 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-ink lg:hidden"
              >
                <X className="size-4.5" />
              </button>
            </div>
          </div>

          {/* Split mode banner */}
          {splitMode ? (
            <div className="border-b border-line bg-brand-50 px-5 py-2.5 text-xs text-brand-700">
              {t('bills.splitHint')}
            </div>
          ) : null}

          {/* Lines (scrollable) */}
          <div className="min-h-0 flex-1 overflow-y-auto">
            {lineCount === 0 ? (
              <p className="px-5 py-10 text-center text-sm text-ink-3">{t('bills.noLines')}</p>
            ) : (
              <ul className="divide-y divide-line">
                {bill.lines.map((line) => (
                  <BillLineItem
                    key={line.id}
                    line={line}
                    locale={locale}
                    currency={currency}
                    splitMode={splitMode}
                    selected={selectedLineIds.has(line.id)}
                    onToggleSelect={() => toggleLineSelection(line.id)}
                    onRemove={() => handleRemoveLine(line.id)}
                    isRemoving={removeLine.isPending}
                  />
                ))}
              </ul>
            )}
          </div>

          {/* Footer: breakdown + remaining + actions */}
          <div className="border-t border-line">
            {/* Remaining total (shown when some lines are paid) */}
            {bill.lines.some((l) => l.paid) && !allLinesPaid ? (
              <div className="border-b border-line px-5 py-3">
                <div className="flex items-baseline justify-between text-sm">
                  <span className="text-ink-3">{t('bills.remaining')}</span>
                  <span className="tnum font-mono font-semibold text-ink">
                    {formatMoney(unpaidTotal, currency, locale)}
                  </span>
                </div>
              </div>
            ) : null}

            {/* Full breakdown (non-split, or when no lines are partially paid) */}
            {bill.breakdown && lineCount > 0 && !bill.lines.some((l) => l.paid) ? (
              <div className="border-b border-line px-5 py-4">
                <BillBreakdown breakdown={bill.breakdown} currency={currency} locale={locale} />
              </div>
            ) : lineCount > 0 && !bill.lines.some((l) => l.paid) ? (
              <div className="flex items-baseline justify-between border-b border-line px-5 py-4 text-sm">
                <span className="text-ink-3">{t('pos.subtotal')}</span>
                <span className="tnum font-mono font-semibold text-ink">
                  {formatMoney(grandTotal, currency, locale)}
                </span>
              </div>
            ) : null}

            {/* Split selection summary */}
            {splitMode && selectedLines.length > 0 ? (
              <div className="border-b border-line bg-brand-50 px-5 py-3">
                <div className="flex items-baseline justify-between text-sm">
                  <span className="font-semibold text-brand-700">
                    {t('bills.splitSelected', { n: selectedLines.length })}
                  </span>
                  <span className="tnum font-mono font-bold text-brand-700">
                    {formatMoney(selectedTotal, currency, locale)}
                  </span>
                </div>
              </div>
            ) : null}

            <div className="flex flex-col gap-2 px-5 py-4">
              {splitMode ? (
                <Button
                  className="w-full"
                  disabled={selectedLines.length === 0 || billQuery.isFetching}
                  onClick={openPayModal}
                >
                  {t('bills.paySplit', { n: selectedLines.length })} ·{' '}
                  {formatMoney(selectedTotal, currency, locale)}
                </Button>
              ) : (
                <Button
                  className="w-full"
                  disabled={unpaidLines.length === 0 || billQuery.isFetching}
                  onClick={openPayModal}
                >
                  {t('bills.pay')} · {formatMoney(unpaidTotal, currency, locale)}
                </Button>
              )}

              <Button
                variant="ghost"
                className="w-full text-xs text-loss hover:text-loss"
                onClick={() => setShowCancelConfirm(true)}
                disabled={cancelBill.isPending}
              >
                {t('bills.cancelBill')}
              </Button>
            </div>

            {removeLine.isError ? (
              <p className="px-5 pb-3 text-xs text-loss" role="alert">
                {(removeLine.error as Error).message}
              </p>
            ) : null}
          </div>
        </aside>
      </div>

      {/* Modifier picker modal */}
      {modifierItem ? (
        <ModifierModal
          item={modifierItem}
          locale={locale}
          onConfirm={(ids) => handleModifierConfirm(ids)}
          onClose={() => setModifierItem(null)}
        />
      ) : null}

      {/* Bill payment modal */}
      {showPayModal && pendingPay ? (
        <BillPaymentModal
          session={session}
          bill={bill}
          locale={locale}
          lineIds={pendingPay.lineIds}
          checkTotalMinor={pendingPay.checkTotalMinor}
          idempotencyKey={pendingPay.idempotencyKey}
          onSuccess={handlePaySuccess}
          onClose={() => {
            setShowPayModal(false)
            setPendingPay(null)
          }}
        />
      ) : null}

      {/* Customer receipt (after paying a check) */}
      {checkResult ? (
        <BillReceiptView
          bill={bill}
          paidLines={checkResult.paidLines}
          checkTotalMinor={checkResult.checkTotalMinor}
          tenderType={checkResult.tenderType}
          tenderedMinor={checkResult.tenderedMinor}
          changeMinor={checkResult.changeMinor}
          locale={locale}
          tableLabel={tableLabel}
          onClose={handleReceiptClose}
        />
      ) : null}

      {/* Kitchen ticket (KOT) */}
      {showKot ? (
        <KotView
          bill={bill}
          lines={unpaidLines}
          locale={locale}
          tableLabel={tableLabel}
          onClose={() => setShowKot(false)}
        />
      ) : null}

      {/* Cancel confirmation dialog */}
      {showCancelConfirm ? (
        <CancelConfirmDialog
          bill={bill}
          isCancelling={cancelBill.isPending}
          error={cancelBill.isError ? (cancelBill.error as Error).message : null}
          onConfirm={handleCancel}
          onClose={() => setShowCancelConfirm(false)}
        />
      ) : null}

      {/* Mobile "view bill" bar */}
      {lineCount > 0 && !billOpen ? (
        <div className="fixed inset-x-0 bottom-0 z-30 flex items-center gap-3 border-t border-line bg-surface px-4 py-3 shadow-lg lg:hidden">
          <div className="min-w-0 flex-1">
            <div className="text-[11px] font-medium uppercase tracking-wide text-ink-3">
              {t('pos.total')}
            </div>
            <div className="tnum font-mono text-lg font-bold text-ink">
              {formatMoney(unpaidTotal, currency, locale)}
            </div>
          </div>
          <Button onClick={() => setBillOpen(true)} className="shrink-0">
            <ReceiptText className="size-4" />
            {t('bills.viewBill')}
            <span className="tnum grid h-5 min-w-5 place-items-center rounded-full bg-white/25 px-1.5 text-xs font-bold">
              {lineCount}
            </span>
          </Button>
        </div>
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// BillLineItem
// ---------------------------------------------------------------------------

function BillLineItem({
  line,
  locale,
  currency,
  splitMode,
  selected,
  onToggleSelect,
  onRemove,
  isRemoving,
}: {
  line: BillLineResponse
  locale: string
  currency: string
  splitMode: boolean
  selected: boolean
  onToggleSelect: () => void
  onRemove: () => void
  isRemoving: boolean
}) {
  const { t } = useTranslation()
  const isPaid = line.paid

  return (
    <li
      className={cn(
        'px-5 py-3 transition-colors',
        isPaid && 'bg-ink-50/40',
        splitMode && !isPaid && selected && 'bg-brand-50',
      )}
    >
      <div className="flex items-center gap-3">
        {/* Split mode: checkbox for unpaid, locked for paid */}
        {splitMode ? (
          isPaid ? (
            <span className="shrink-0" aria-hidden="true">
              <Badge tone="emerald" className="text-[10px] px-1.5 py-0">
                {t('bills.paid')}
              </Badge>
            </span>
          ) : (
            <input
              type="checkbox"
              checked={selected}
              onChange={onToggleSelect}
              aria-label={t('bills.selectLine', { name: line.nameSnapshot })}
              className="size-4 shrink-0 cursor-pointer accent-brand-500 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
            />
          )
        ) : null}

        <div className="min-w-0 flex-1">
          <div className={cn('truncate text-sm font-medium', isPaid ? 'text-ink-3 line-through' : 'text-ink')}>
            {line.nameSnapshot}
          </div>
          <div className="tnum mt-0.5 font-mono text-xs text-ink-3">
            {line.qty > 1 ? `${line.qty} × ` : ''}
            {formatMoney(line.unitPriceMinor + line.modifierDeltaMinor, currency, locale)}
          </div>
        </div>
        <div className={cn('tnum shrink-0 font-mono text-sm font-medium', isPaid ? 'text-ink-3' : 'text-ink')}>
          {formatMoney(line.lineTotalMinor, currency, locale)}
        </div>

        {/* Paid badge (non-split mode) */}
        {!splitMode && isPaid ? (
          <Badge tone="emerald" className="shrink-0 text-[10px] px-1.5 py-0">
            {t('bills.paid')}
          </Badge>
        ) : null}

        {/* Remove button — only for unpaid lines in non-split mode */}
        {!splitMode && !isPaid ? (
          <button
            type="button"
            onClick={onRemove}
            disabled={isRemoving}
            aria-label={t('bills.removeLine', { name: line.nameSnapshot })}
            className="grid size-7 shrink-0 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-loss focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            <Trash2 className="size-3.5" />
          </button>
        ) : null}
      </div>
      {/* Modifier names */}
      {line.modifiers.length > 0 ? (
        <div className={cn('mt-1 flex flex-wrap gap-1', splitMode ? 'pl-7' : 'pl-0')}>
          {line.modifiers.map((mod) => (
            <span key={mod.optionId} className="text-[11px] leading-tight text-ink-3">
              {mod.nameSnapshot}
            </span>
          ))}
        </div>
      ) : null}
    </li>
  )
}

// ---------------------------------------------------------------------------
// BillBreakdown
// ---------------------------------------------------------------------------

function BillBreakdown({
  breakdown,
  currency,
  locale,
}: {
  breakdown: PriceBreakdownResponse
  currency: string
  locale: string
}) {
  const { t } = useTranslation()
  return (
    <div className="space-y-2 text-sm">
      <div className="flex items-baseline justify-between">
        <span className="text-ink-3">{t('pos.subtotal')}</span>
        <span className="tnum font-mono text-ink">
          {formatMoney(breakdown.subtotalMinor, currency, locale)}
        </span>
      </div>
      {breakdown.discountMinor > 0 ? (
        <div className="flex items-baseline justify-between">
          <span className="text-ink-3">{t('pos.discount')}</span>
          <span className="tnum font-mono text-loss">
            − {formatMoney(breakdown.discountMinor, currency, locale)}
          </span>
        </div>
      ) : null}
      <div className="flex items-baseline justify-between">
        <span className="text-ink-3">{t('pos.serviceCharge')}</span>
        <span className="tnum font-mono text-ink">
          {formatMoney(breakdown.serviceChargeMinor, currency, locale)}
        </span>
      </div>
      <div className="flex items-baseline justify-between">
        <span className="text-ink-3">{t('pos.tax')}</span>
        <span className="tnum font-mono text-ink">
          {formatMoney(breakdown.taxMinor, currency, locale)}
        </span>
      </div>
      <div className="flex items-baseline justify-between border-t border-line pt-2">
        <span className="font-semibold text-ink">{t('pos.total')}</span>
        <span className="tnum font-mono text-xl font-bold text-ink">
          {formatMoney(breakdown.grandTotalMinor, currency, locale)}
        </span>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// BillItemCard (lighter version of POS ItemCard — no qty badge, just add)
// ---------------------------------------------------------------------------

function BillItemCard({
  item,
  locale,
  isAdding,
  onAdd,
}: {
  item: MenuItem
  locale: string
  isAdding: boolean
  onAdd: () => void
}) {
  const { t } = useTranslation()
  const stockSoldOut = item.stockQuantity != null && item.stockQuantity <= 0
  const unavailable = !item.available || stockSoldOut

  return (
    <button
      type="button"
      onClick={unavailable || isAdding ? undefined : onAdd}
      disabled={unavailable}
      aria-label={
        unavailable
          ? t('pos.soldOutLabel', { name: item.name })
          : t('bills.addToBill', { name: item.name })
      }
      aria-disabled={unavailable}
      className={cn(
        'relative flex flex-col items-start rounded-2xl border bg-surface text-left shadow-sm transition-all overflow-hidden',
        unavailable
          ? 'cursor-not-allowed border-line opacity-55'
          : 'border-line hover:-translate-y-0.5 hover:border-brand-300 hover:shadow-md',
      )}
    >
      {/* Item photo — fixed 4:3 aspect, object-cover; omitted when no image */}
      {item.imageUrl ? (
        <div className="aspect-[4/3] w-full overflow-hidden">
          <img
            src={item.imageUrl}
            alt={item.name}
            className="h-full w-full object-cover"
          />
        </div>
      ) : null}

      {/* Content area */}
      <div className="flex flex-col p-4 w-full">
        {unavailable ? (
          <span className="absolute right-3 top-3">
            <Badge tone="neutral" className="px-1.5 py-0 text-[10px]">
              {t('pos.soldOut')}
            </Badge>
          </span>
        ) : null}

        <span className={cn('font-semibold', unavailable ? 'text-ink-3' : 'text-ink')}>
          {item.name}
        </span>
        <span
          className={cn(
            'tnum mt-2 font-mono text-sm font-semibold',
            unavailable ? 'text-ink-3/50' : 'text-brand-700',
          )}
        >
          {formatMoney(item.priceMinor, item.currency, locale)}
        </span>
        {item.modifierGroups.length > 0 && !unavailable ? (
          <span className="mt-1.5 text-[11px] text-ink-3">{t('pos.hasOptions')}</span>
        ) : null}
      </div>
    </button>
  )
}

// ---------------------------------------------------------------------------
// CancelConfirmDialog
// ---------------------------------------------------------------------------

function CancelConfirmDialog({
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
      className="fixed inset-0 z-[60] grid place-items-center bg-black/40 p-4 backdrop-blur-sm"
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

// ---------------------------------------------------------------------------
// Module-level pure helpers
// ---------------------------------------------------------------------------

/**
 * Generates a fresh idempotency key per check-pay attempt.
 * Defined outside the component to avoid react-hooks/purity lint errors.
 */
function freshIdempotencyKey(): string {
  // crypto.randomUUID is available in all modern browsers (Chrome 92+, Firefox 95+, Safari 15.4+)
  // and in Node 14.17+. It is a secure, pure-per-call function; lint is satisfied here because
  // this function is not inside the render path.
  return crypto.randomUUID()
}

// ---------------------------------------------------------------------------
// Helpers (mirrors Pos.tsx helpers)
// ---------------------------------------------------------------------------

interface VirtualCategory {
  id: string
  name: string
  legacyKey: string
}

function deriveCategories(
  items: MenuItem[],
  backendCategories: CategoryResponse[],
): VirtualCategory[] {
  const result: VirtualCategory[] = backendCategories.map((c) => ({
    id: c.id,
    name: c.name,
    legacyKey: c.name.toLowerCase(),
  }))

  const backendIds = new Set(backendCategories.map((c) => c.id))
  const legacyKeys = new Set<string>()
  for (const item of items) {
    if (!item.categoryId || !backendIds.has(item.categoryId)) {
      if (item.category && !legacyKeys.has(item.category)) {
        legacyKeys.add(item.category)
        const covered = backendCategories.some(
          (c) => c.name.toLowerCase() === item.category.toLowerCase(),
        )
        if (!covered) {
          result.push({ id: item.category, name: item.category, legacyKey: item.category })
        }
      }
    }
  }

  return result
}
