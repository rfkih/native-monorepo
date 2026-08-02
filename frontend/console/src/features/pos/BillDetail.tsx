/**
 * BillDetail — 3b redesign: bottom-sheet overlay when sheetOpen=true (tablet+),
 * full-page overlay on phone.
 *
 * All existing logic (bill data, line add/remove, split mode, payment, KOT,
 * receipt, cancel) is preserved exactly — only the presentation layer changed.
 *
 * Money rule (rule 8): all amounts are integer minor units, rendered via formatMoney().
 * Strings rule (rule 9): every user-facing string is an i18n key.
 */
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  ArrowLeft,
  Table2,
  ReceiptText,
  ChefHat,
  SplitSquareHorizontal,
  ChevronDown,
  Send,
} from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import {
  useMenu,
  useCategories,
  type MenuItem,
} from './api'
import { ModifierModal } from './ModifierModal'
import { deriveCategories, visibleMenuItems,
} from './lib/categories'
import { useMediaQuery } from './lib/useMediaQuery'
import { PhoneSheetContent } from './components/PhoneSheetContent'
import { BillLineItem } from './components/BillLineItem'
import { BillBreakdown } from './components/BillBreakdown'
import { CancelConfirmDialog } from './components/CancelConfirmDialog'
import { BillPaymentModal } from './BillPaymentModal'
import { BillReceiptView } from './BillReceiptView'
import { KotView } from './KotView'
import {
  useBill,
  useAppendLines,
  useRemoveLine,
  useCancelBill,
  type BillLineResponse,
} from './billsApi'

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface Props {
  session: CompanySession
  locale: string
  billId: string
  /** Label of the table the bill is on, or null */
  tableLabel: string | null
  /** When true the component renders as a bottom sheet (tablet+) */
  sheetOpen: boolean
  onSheetOpenChange: (open: boolean) => void
  /** P4: each increment fires the kitchen ticket as soon as the bill is loaded (dock Send). */
  autoKotToken?: number
  /** P4: each increment opens the pay modal for the full unpaid check (dock Pay). */
  autoPayToken?: number
  onBack: () => void
  onPaid: () => void
}

// ---------------------------------------------------------------------------
// BillDetail
// ---------------------------------------------------------------------------

export function BillDetail({
  session,
  locale,
  billId,
  tableLabel,
  sheetOpen,
  onSheetOpenChange,
  autoKotToken = 0,
  autoPayToken = 0,
  onBack,
  onPaid,
}: Props) {
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
  const [billOpen, setBillOpen] = useState(false) // phone-only: bill rail drawer

  // ─── Split mode ────────────────────────────────────────────────────────────
  const [splitMode, setSplitMode] = useState(false)
  const [selectedLineIds, setSelectedLineIds] = useState<Set<string>>(new Set())

  // ─── Receipt state ─────────────────────────────────────────────────────────
  interface CheckResult {
    paidLines: BillLineResponse[]
    checkTotalMinor: number
    tenderType: 'CASH' | 'QRIS' | 'CARD'
    tenderedMinor?: number
    changeMinor?: number
  }
  const [checkResult, setCheckResult] = useState<CheckResult | null>(null)

  // ─── KOT ──────────────────────────────────────────────────────────────────
  const [showKot, setShowKot] = useState(false)

  // ─── Category (for adding items) ──────────────────────────────────────────
  const [activeCategoryId, setActiveCategoryId] = useState<string | null>(null)
  const orderedCategories = deriveCategories(items, categories)
  const resolvedCategoryId: string = activeCategoryId ?? orderedCategories[0]?.id ?? ''

  const visibleItems = visibleMenuItems(items, orderedCategories, resolvedCategoryId, '')

  const currency = bill?.currency ?? items[0]?.currency ?? session.baseCurrency
  const lineCount = bill?.lines.reduce((s, l) => s + l.qty, 0) ?? 0

  const unpaidLines = bill?.lines.filter((l) => !l.paid) ?? []
  const unpaidTotal = unpaidLines.reduce((s, l) => s + l.lineTotalMinor, 0)
  const grandTotal =
    bill?.breakdown?.grandTotalMinor ??
    bill?.lines.reduce((s, l) => s + l.lineTotalMinor, 0) ??
    0

  const selectedLines = unpaidLines.filter((l) => selectedLineIds.has(l.id))
  const selectedTotal = selectedLines.reduce((s, l) => s + l.lineTotalMinor, 0)

  // ---------------------------------------------------------------------------
  // Handlers (logic unchanged)
  // ---------------------------------------------------------------------------

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

  interface PendingPayInfo {
    lineIds?: string[]
    checkTotalMinor: number
    idempotencyKey: string
    paidLineObjects: BillLineResponse[]
  }
  const [pendingPay, setPendingPay] = useState<PendingPayInfo | null>(null)

  // Must run on EVERY render — a hook below the loading/non-OPEN early returns changes the hook
  // count between the loading render and the loaded render, which crashes React ("Rendered more
  // hooks than during the previous render") and blanks the whole POS on every freshly opened bill.
  const isTablet = useMediaQuery('(min-width: 640px)')

  // P4 dock verbs: consume each token once, as soon as the bill has loaded and is OPEN. A token
  // arriving while the bill is still loading waits for the next effect run (isLoading in deps).
  const consumedKotToken = useRef(0)
  const consumedPayToken = useRef(0)
  const billReady = !billQuery.isLoading && !!bill && bill.status === 'OPEN'
  useEffect(() => {
    if (!autoKotToken || autoKotToken === consumedKotToken.current || !billReady) return
    consumedKotToken.current = autoKotToken
    setShowKot(true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoKotToken, billReady])
  useEffect(() => {
    if (!autoPayToken || autoPayToken === consumedPayToken.current || !billReady) return
    consumedPayToken.current = autoPayToken
    openPayModal()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoPayToken, billReady])

  function openPayModal() {
    if (splitMode) {
      setPendingPay({
        lineIds: selectedLines.map((l) => l.id),
        checkTotalMinor: selectedTotal,
        idempotencyKey: freshIdempotencyKey(),
        paidLineObjects: selectedLines,
})
    } else {
      setPendingPay({
        lineIds: undefined,
        checkTotalMinor: unpaidTotal,
        idempotencyKey: freshIdempotencyKey(),
        paidLineObjects: unpaidLines,
})
    }
    setShowPayModal(true)
  }

  function handlePaySuccess() {
    setShowPayModal(false)
    void qc.invalidateQueries({ queryKey: ['bill', session.companyId, billId] })
    if (pendingPay) {
      setCheckResult({
        paidLines: pendingPay.paidLineObjects,
        checkTotalMinor: pendingPay.checkTotalMinor,
        tenderType: 'CASH',
})
    }
    setPendingPay(null)
    setSelectedLineIds(new Set())
    setSplitMode(false)
  }

  function handleReceiptClose() {
    setCheckResult(null)
    const latestBill = billQuery.data
    if (!latestBill || latestBill.status === 'PAID') {
      onPaid()
    }
  }

  // ---------------------------------------------------------------------------
  // Loading / non-OPEN states
  // ---------------------------------------------------------------------------

  if (billQuery.isLoading || !bill) {
    return (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-paper">
        <Spinner />
      </div>
    )
  }

  if (bill.status !== 'OPEN') {
    return (
      <div className="fixed inset-0 z-50 flex flex-col bg-paper">
        <div className="flex items-center gap-3 border-b border-line bg-surface px-4 py-3">
          <button
            type="button"
            onClick={onBack}
            aria-label={t('bills.backToBills')}
            className="grid size-9 place-items-center rounded-xl border border-line text-ink-3 hover:bg-hover"
          >
            <ArrowLeft className="size-[18px]" />
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

  // ---------------------------------------------------------------------------
  // 3b Sheet layout
  // ---------------------------------------------------------------------------

  // How many lines are NOT yet sent (simplified: all unpaid lines = "pending send")
  const unsentCount = unpaidLines.filter((l) => !l.paid).length

  return (
    <>
      {/* Bottom sheet — slides up from bottom of screen */}
      {/* On phone (<sm) it's full-screen when billOpen is true */}
      {/* On sm+ it's a bottom sheet whose height depends on sheetOpen */}

      {/* Backdrop (only when sheetOpen on tablet+) */}
      {sheetOpen ? (
        <div
          className="fixed inset-0 z-40 hidden bg-black/20 backdrop-blur-[2px] sm:block"
          onClick={() => onSheetOpenChange(false)}
          aria-hidden="true"
        />
      ) : null}

      {/* Full-page phone sheet — mounted only on phone-sized viewports */}
      {!isTablet ? (
        <div
          className={cn(
            'fixed inset-0 z-50 flex flex-col bg-paper transition-transform duration-300 ease-out',
            billOpen ? 'translate-y-0' : 'translate-y-full',
          )}
          role="dialog"
          aria-modal="true"
          aria-label={t('bills.trayTitle')}
        >
          <PhoneSheetContent
            bill={bill}
            items={items}
            visibleItems={visibleItems}
            orderedCategories={orderedCategories}
            resolvedCategoryId={resolvedCategoryId}
            activeCategoryId={activeCategoryId}
            setActiveCategoryId={setActiveCategoryId}
            tableLabel={tableLabel}
            locale={locale}
            currency={currency}
            lineCount={lineCount}
            unpaidLines={unpaidLines}
            unpaidTotal={unpaidTotal}
            grandTotal={grandTotal}
            selectedLines={selectedLines}
            selectedTotal={selectedTotal}
            selectedLineIds={selectedLineIds}
            splitMode={splitMode}
            allLinesPaid={allLinesPaid}
            appendLines={appendLines}
            removeLine={removeLine}
            isRemoving={removeLine.isPending}
            onItemTap={handleItemTap}
            onToggleSplitMode={toggleSplitMode}
            onToggleLineSelect={toggleLineSelection}
            onRemoveLine={handleRemoveLine}
            onKot={() => setShowKot(true)}
            onCancel={() => setShowCancelConfirm(true)}
            onPayModal={openPayModal}
            onClose={() => setBillOpen(false)}
            onBack={onBack}
          />
        </div>
      ) : null}

      {/* Tablet+ bottom sheet — mounted only on sm+ viewports */}
      {isTablet ? (
        <div
          className={cn(
            'fixed inset-x-0 bottom-0 z-50 flex flex-col rounded-t-[28px] bg-surface shadow-[0_-16px_48px_rgba(15,23,42,.18)] transition-transform duration-300 ease-out',
            sheetOpen ? 'translate-y-0' : 'translate-y-full',
            'max-h-[80dvh]',
          )}
          style={{ minHeight: sheetOpen ? '540px' : undefined }}
          role="dialog"
          aria-modal="true"
          aria-label={bill.guestLabel}
        >
        {/* Drag handle */}
        <div className="flex shrink-0 flex-col items-center pt-3 pb-1">
          <div className="h-1 w-11 rounded-full bg-line" aria-hidden="true" />
        </div>

        {/* Sheet header */}
        <div className="flex shrink-0 items-center gap-2 border-b border-line px-5 py-3">
          <h2 className="font-display text-lg font-bold text-ink">{bill.guestLabel}</h2>
          {tableLabel ? (
            <span className="flex items-center gap-1 text-sm text-ink-3">
              <Table2 className="size-3.5" aria-hidden="true" />
              {tableLabel}
            </span>
          ) : null}
          <div className="flex-1" />
          {/* KOT */}
          <button
            type="button"
            onClick={() => setShowKot(true)}
            aria-label={t('kot.title')}
            disabled={lineCount === 0}
            className="flex h-9 items-center gap-1.5 rounded-xl border border-line bg-surface px-3 text-sm font-semibold text-ink-2 hover:bg-hover disabled:opacity-40 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <ChefHat className="size-4" aria-hidden="true" />
            {t('kot.title')}
          </button>
          {/* Split toggle */}
          {unpaidLines.length > 1 ? (
            <button
              type="button"
              onClick={toggleSplitMode}
              aria-pressed={splitMode}
              aria-label={t('bills.splitToggle')}
              className={cn(
                'flex h-9 items-center gap-1.5 rounded-xl border px-3 text-sm font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                splitMode
                  ? 'border-emerald bg-emerald-tint text-emerald-2'
                  : 'border-line bg-surface text-ink-2 hover:bg-hover',
              )}
            >
              <SplitSquareHorizontal className="size-4" aria-hidden="true" />
              {t('bills.split')}
            </button>
          ) : null}
          <button
            type="button"
            onClick={() => onSheetOpenChange(false)}
            aria-label={t('common.close')}
            className="grid size-9 place-items-center rounded-xl text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <ChevronDown className="size-5" aria-hidden="true" />
          </button>
        </div>

        {/* Split hint */}
        {splitMode ? (
          <div className="shrink-0 border-b border-line bg-emerald-tint px-5 py-2 text-xs font-medium text-emerald-2">
            {t('bills.splitHint')}
          </div>
        ) : null}

        {/* Bill lines (scrollable body) */}
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
          {appendLines.isError ? (
            <p className="mx-5 mt-2 text-xs text-loss" role="alert">
              {(appendLines.error as Error).message}
            </p>
          ) : null}
        </div>

        {/* Totals block */}
        <div className="shrink-0 border-t border-line">
          {bill.breakdown && lineCount > 0 && !bill.lines.some((l) => l.paid) ? (
            <div className="border-b border-line px-5 py-3">
              <BillBreakdown breakdown={bill.breakdown} currency={currency} locale={locale} />
            </div>
          ) : lineCount > 0 ? (
            <div className="border-b border-line px-5 py-3">
              <div className="flex items-baseline justify-between text-sm">
                <span className="text-ink-3">{t('pos.subtotal')}</span>
                <span className="tnum font-mono font-semibold text-ink">
                  {formatMoney(
                    bill.lines.some((l) => l.paid) ? unpaidTotal : grandTotal,
                    currency,
                    locale,
                  )}
                </span>
              </div>
              {bill.lines.some((l) => l.paid) && !allLinesPaid ? (
                <div className="mt-1.5 flex items-baseline justify-between text-sm">
                  <span className="text-ink-3">{t('bills.remaining')}</span>
                  <span className="tnum font-mono font-semibold text-ink">
                    {formatMoney(unpaidTotal, currency, locale)}
                  </span>
                </div>
              ) : null}
            </div>
          ) : null}

          {/* Split selection summary */}
          {splitMode && selectedLines.length > 0 ? (
            <div className="border-b border-line bg-emerald-tint px-5 py-2.5">
              <div className="flex items-baseline justify-between text-sm">
                <span className="font-semibold text-emerald-2">
                  {t('bills.splitSelected', { n: selectedLines.length })}
                </span>
                <span className="tnum font-mono font-bold text-emerald-2">
                  {formatMoney(selectedTotal, currency, locale)}
                </span>
              </div>
            </div>
          ) : null}

          {/* Footer action row */}
          <div className="flex items-center gap-2.5 px-5 py-4">
            {/* Send to kitchen (secondary) */}
            {unsentCount > 0 && !splitMode ? (
              <button
                type="button"
                onClick={() => setShowKot(true)}
                className="flex h-[60px] shrink-0 items-center gap-2 rounded-xl border border-emerald-line bg-emerald-tint px-5 text-[15px] font-bold text-emerald-2 transition-all hover:bg-emerald-tint/80 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
              >
                <Send className="size-[17px]" aria-hidden="true" />
                {t('bills.sendToKitchen', { n: unsentCount })}
              </button>
            ) : null}

            {/* Pay (primary) — full-width when split not active */}
            {splitMode ? (
              <button
                type="button"
                data-testid="bill-pay-split"
                disabled={selectedLines.length === 0 || billQuery.isFetching}
                onClick={openPayModal}
                className="tnum h-[60px] flex-1 rounded-xl bg-emerald px-6 font-mono text-[15px] font-bold text-on-emerald transition-all hover:bg-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald disabled:opacity-40"
              >
                {t('bills.paySplit', { n: selectedLines.length })} ·{' '}
                {formatMoney(selectedTotal, currency, locale)}
              </button>
            ) : (
              <button
                type="button"
                data-testid="bill-pay"
                disabled={unpaidLines.length === 0 || billQuery.isFetching}
                onClick={openPayModal}
                className="tnum h-[60px] flex-1 rounded-xl bg-emerald px-6 font-mono text-[15px] font-bold text-on-emerald transition-all hover:bg-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald disabled:opacity-40"
              >
                {allLinesPaid
                  ? t('bills.allPaid')
                  : t('bills.payTotal', { total: formatMoney(unpaidTotal, currency, locale) })}
              </button>
            )}
          </div>

          {/* Cancel link */}
          <div className="pb-4 text-center">
            <button
              type="button"
              onClick={() => setShowCancelConfirm(true)}
              disabled={cancelBill.isPending}
              className="text-xs text-ink-3 underline hover:text-loss focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
            >
              {t('bills.cancelBill')}
            </button>
          </div>

          {removeLine.isError ? (
            <p className="px-5 pb-3 text-xs text-loss" role="alert">
              {(removeLine.error as Error).message}
            </p>
          ) : null}
        </div>
        </div>
      ) : null}

      {/* Phone: SummaryBar equivalent — tap to open phone sheet */}
      {!isTablet && lineCount > 0 && !billOpen ? (
        <div className="fixed inset-x-0 bottom-0 z-30 flex items-center gap-3 border-t border-line bg-surface px-4 py-3 shadow-lg">
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

      {/* Cancel confirmation */}
      {showCancelConfirm ? (
        <CancelConfirmDialog
          bill={bill}
          isCancelling={cancelBill.isPending}
          error={cancelBill.isError ? (cancelBill.error as Error).message : null}
          onConfirm={handleCancel}
          onClose={() => setShowCancelConfirm(false)}
        />
      ) : null}
    </>
  )
}

// ---------------------------------------------------------------------------
// Module-level helpers
// ---------------------------------------------------------------------------

function freshIdempotencyKey(): string {
  return crypto.randomUUID()
}

// deriveCategories/VirtualCategory now come from ./lib/categories (redesign P1) — this file
// carried a STALE case-sensitive fork of the category-adoption logic (missed fix f050be1).
