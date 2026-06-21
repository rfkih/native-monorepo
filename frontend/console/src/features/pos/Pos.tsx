import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Minus, Plus, Trash2, Utensils, Info, PauseCircle, ClipboardList, Table2, Settings } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Segmented } from '@/components/ui/Segmented'
import { useSession, type CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { cn } from '@/lib/cn'
import { formatMoney, isoMinorExponent } from '@/lib/money'
import {
  useMenu,
  useCategories,
  useSeedMenu,
  useQuote,
  useTables,
  useParkOrder,
  useParkedOrders,
  useGetOrder,
  type MenuItem,
  type CategoryResponse,
  type OrderResponse,
  type PaymentResponse,
  type PriceBreakdownResponse,
  type OrderLineInput,
  type TableResponse,
} from './api'
import { PaymentModal } from './PaymentModal'
import { ReceiptView } from './ReceiptView'
import { ModifierModal } from './ModifierModal'
import { TableManagement } from './TableManagement'
import { ParkedTray } from './ParkedTray'

// ---------------------------------------------------------------------------
// Order types
// ---------------------------------------------------------------------------

type OrderType = 'DINE_IN' | 'TAKEAWAY' | 'DELIVERY'

// ---------------------------------------------------------------------------
// Cart line — extends OrderLineInput with the display info we need client-side
// ---------------------------------------------------------------------------

interface CartLine {
  menuItemId: string
  qty: number
  selectedOptionIds: string[]
  /** Effective unit price (base + Σ modifier deltas) in minor units. */
  effectiveUnitPriceMinor: number
  /** Human-readable names of selected options — shown under the item in the cart. */
  selectedOptionNames: string[]
}

export function Pos() {
  const { company } = useSession()
  if (!company) return <NoCompany />
  return <PosInner session={company} />
}

function PosInner({ session }: { session: CompanySession }) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const menuQuery = useMenu(session)
  const categoriesQuery = useCategories(session)
  const tablesQuery = useTables(session)
  const parkedQuery = useParkedOrders(session)
  const seed = useSeedMenu(session)
  const parkOrder = useParkOrder(session)

  // Cart: list of CartLine
  const [cart, setCart] = useState<CartLine[]>([])

  // Phase 4: order type + table
  const [orderType, setOrderType] = useState<OrderType>('DINE_IN')
  const [selectedTableId, setSelectedTableId] = useState<string | null>(null)

  // discountInput is the raw string the user types; discountMinor is the parsed integer.
  const [discountInput, setDiscountInput] = useState<string>('')
  const [discountError, setDiscountError] = useState<string | null>(null)

  // Modal / panel state
  const [modal, setModal] = useState<'payment' | 'receipt' | null>(null)
  const [modifierItem, setModifierItem] = useState<MenuItem | null>(null)
  const [placedOrder, setPlacedOrder] = useState<OrderResponse | null>(null)
  const [placedPayment, setPlacedPayment] = useState<PaymentResponse | null>(null)
  const [showTableMgmt, setShowTableMgmt] = useState(false)
  const [showParkedTray, setShowParkedTray] = useState(false)

  // Resume: parked order being resumed (loaded via useGetOrder)
  const [resumingOrderId, setResumingOrderId] = useState<string | null>(null)
  const [resumedOrder, setResumedOrder] = useState<OrderResponse | null>(null)
  const resumeQuery = useGetOrder(session, resumingOrderId)

  // When the resumed order loads, populate cart state
  const [resumeLoaded, setResumeLoaded] = useState(false)

  if (resumeQuery.data && resumingOrderId && !resumeLoaded) {
    const ro = resumeQuery.data
    // Rebuild cart lines from the resumed order
    const rebuiltCart: CartLine[] = ro.lines.map((l) => ({
      menuItemId: l.menuItemId,
      qty: l.qty,
      selectedOptionIds: l.modifiers.map((m) => m.optionId),
      effectiveUnitPriceMinor: l.unitPriceMinor,
      selectedOptionNames: l.modifiers.map((m) => m.nameSnapshot),
    }))
    setCart(rebuiltCart)
    setOrderType((ro.orderType as OrderType) ?? 'DINE_IN')
    setSelectedTableId(ro.tableId ?? null)
    setResumedOrder(ro)
    setResumeLoaded(true)
    setShowParkedTray(false)
  }

  // Active category tab — null means "all" / first available
  const [activeCategoryId, setActiveCategoryId] = useState<string | null>(null)

  const items = menuQuery.data ?? []
  const categories = (categoriesQuery.data ?? []).filter((c) => c.active)
  const tables = (tablesQuery.data ?? []).filter((tbl) => tbl.active)
  const parkedCount = parkedQuery.data?.length ?? 0

  const currency = items[0]?.currency ?? session.baseCurrency

  // Parse discount: the input is in major units (e.g. "5000" IDR or "5.00" USD).
  const discountMinor = parseDiscountInput(discountInput, currency)

  // Build API-shaped lines for quote/checkout
  const cartLines: OrderLineInput[] = cart.map(({ menuItemId, qty, selectedOptionIds }) => ({
    menuItemId,
    qty,
    selectedOptionIds,
  }))

  const lineCount = cart.reduce((sum, l) => sum + l.qty, 0)

  // Live price quote from the server
  const quoteQuery = useQuote(session, cartLines, discountMinor)
  const breakdown = quoteQuery.data ?? resumedOrder?.breakdown ?? null

  // Authoritative grand total
  const clientSubtotalMinor = cart.reduce(
    (sum, l) => sum + l.effectiveUnitPriceMinor * l.qty,
    0,
  )
  const grandTotalMinor = breakdown?.grandTotalMinor ?? (resumedOrder?.totalMinor ?? clientSubtotalMinor)

  // ---------------------------------------------------------------------------
  // Category grouping
  // ---------------------------------------------------------------------------

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

  // ---------------------------------------------------------------------------
  // Order type options
  // ---------------------------------------------------------------------------

  const orderTypeOptions: { value: OrderType; label: string }[] = [
    { value: 'DINE_IN', label: t('pos.orderType.dineIn') },
    { value: 'TAKEAWAY', label: t('pos.orderType.takeaway') },
    { value: 'DELIVERY', label: t('pos.orderType.delivery') },
  ]

  function handleOrderTypeChange(v: OrderType) {
    setOrderType(v)
    // Clear table selection when switching away from dine-in
    if (v !== 'DINE_IN') setSelectedTableId(null)
  }

  // ---------------------------------------------------------------------------
  // Cart manipulation
  // ---------------------------------------------------------------------------

  function handleItemTap(item: MenuItem) {
    if (item.modifierGroups.length > 0) {
      setModifierItem(item)
      return
    }
    addToCart(item.id, [], item.priceMinor, [])
  }

  function addToCart(
    menuItemId: string,
    selectedOptionIds: string[],
    effectiveUnitPriceMinor: number,
    selectedOptionNames: string[],
  ) {
    setCart((prev) => {
      const key = lineKey(menuItemId, selectedOptionIds)
      const idx = prev.findIndex((l) => lineKey(l.menuItemId, l.selectedOptionIds) === key)
      if (idx !== -1) {
        const next = [...prev]
        next[idx] = { ...next[idx], qty: next[idx].qty + 1 }
        return next
      }
      return [
        ...prev,
        { menuItemId, qty: 1, selectedOptionIds, effectiveUnitPriceMinor, selectedOptionNames },
      ]
    })
  }

  function handleModifierConfirm(selectedOptionIds: string[], effectivePriceMinor: number) {
    if (!modifierItem) return
    const names = modifierItem.modifierGroups
      .flatMap((g) => g.options)
      .filter((o) => selectedOptionIds.includes(o.id))
      .map((o) => o.name)
    addToCart(modifierItem.id, selectedOptionIds, effectivePriceMinor, names)
    setModifierItem(null)
  }

  function decCartLine(index: number) {
    setCart((prev) => {
      const next = [...prev]
      const line = next[index]
      if (line.qty <= 1) {
        next.splice(index, 1)
      } else {
        next[index] = { ...line, qty: line.qty - 1 }
      }
      return next
    })
  }

  // ---------------------------------------------------------------------------
  // Discount
  // ---------------------------------------------------------------------------

  function handleDiscountChange(raw: string) {
    setDiscountInput(raw)
    if (raw === '' || raw === '0') {
      setDiscountError(null)
      return
    }
    const parsed = Number(raw)
    if (isNaN(parsed) || parsed < 0) {
      setDiscountError(t('pos.discountInvalid'))
    } else {
      setDiscountError(null)
    }
  }

  // ---------------------------------------------------------------------------
  // Hold / Park
  // ---------------------------------------------------------------------------

  function handleHold() {
    if (lineCount === 0) return
    parkOrder.mutate(
      {
        lines: cartLines,
        orderType,
        tableId: orderType === 'DINE_IN' ? selectedTableId : null,
        discountMinor: discountMinor > 0 ? discountMinor : undefined,
      },
      {
        onSuccess: () => {
          clearCart()
        },
      },
    )
  }

  function clearCart() {
    setCart([])
    setDiscountInput('')
    setDiscountError(null)
    setResumingOrderId(null)
    setResumedOrder(null)
    setResumeLoaded(false)
    setSelectedTableId(null)
  }

  // ---------------------------------------------------------------------------
  // Resume
  // ---------------------------------------------------------------------------

  function handleResume(orderId: string) {
    setResumingOrderId(orderId)
    setResumeLoaded(false)
    setResumedOrder(null)
  }

  // ---------------------------------------------------------------------------
  // Payment
  // ---------------------------------------------------------------------------

  function openPayment() {
    setModal('payment')
  }

  function handlePaymentSuccess(order: OrderResponse, payment: PaymentResponse) {
    setPlacedOrder(order)
    setPlacedPayment(payment)
    clearCart()
    setModal('receipt')
  }

  function handleNewOrder() {
    setPlacedOrder(null)
    setPlacedPayment(null)
    setModal(null)
  }

  // ---------------------------------------------------------------------------
  // Determine selected table object (for display)
  // ---------------------------------------------------------------------------

  const selectedTable = tables.find((t) => t.tableId === selectedTableId) ?? null

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_360px]">
      {/* Menu */}
      <div>
        <header className="mb-5">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1 className="font-display text-3xl font-semibold tracking-tight text-ink">
                {t('pos.title')}
              </h1>
              <p className="mt-1 text-sm text-ink-3">{t('pos.subtitle', { name: session.name })}</p>
            </div>

            {/* Parked tray button */}
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setShowParkedTray(true)}
                className={cn(
                  'flex items-center gap-1.5 rounded-lg border px-3 py-2 text-sm font-medium transition-colors',
                  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                  parkedCount > 0
                    ? 'border-amber/40 bg-amber-tint text-amber-2 hover:bg-amber-tint/70'
                    : 'border-line-strong bg-surface text-ink-2 hover:border-ink-3',
                )}
                aria-label={t('pos.parked.trayTitle')}
              >
                <ClipboardList className="size-4" aria-hidden="true" />
                {t('pos.parked.parkedLabel')}
                {parkedCount > 0 ? (
                  <Badge tone="amber" className="ml-1 text-[10px] px-1.5 py-0">
                    {parkedCount}
                  </Badge>
                ) : null}
              </button>
            </div>
          </div>

          {/* Order type selector */}
          <div className="mt-4 flex flex-wrap items-center gap-3">
            <Segmented
              options={orderTypeOptions}
              value={orderType}
              onChange={handleOrderTypeChange}
              ariaLabel={t('pos.orderType.label')}
            />

            {/* Table management shortcut (dine-in only) */}
            {orderType === 'DINE_IN' ? (
              <button
                type="button"
                onClick={() => setShowTableMgmt(true)}
                className={cn(
                  'flex items-center gap-1 rounded-lg border border-line-strong bg-surface px-2.5 py-1.5 text-xs text-ink-2',
                  'hover:border-ink-3 transition-colors',
                  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                )}
                aria-label={t('pos.table.management')}
              >
                <Settings className="size-3.5" aria-hidden="true" />
                {t('pos.table.management')}
              </button>
            ) : null}
          </div>

          {/* Table picker — shown when DINE_IN */}
          {orderType === 'DINE_IN' ? (
            <TablePicker
              tables={tables}
              selectedTableId={selectedTableId}
              onSelect={setSelectedTableId}
              isLoading={tablesQuery.isLoading}
            />
          ) : null}
        </header>

        {menuQuery.isLoading ? (
          <div className="grid place-items-center py-24 text-emerald">
            <Spinner />
          </div>
        ) : items.length === 0 ? (
          <Card className="p-10 text-center">
            <div className="mx-auto grid size-12 place-items-center rounded-full bg-emerald-tint text-emerald">
              <Utensils className="size-6" />
            </div>
            <h2 className="mt-4 font-display text-xl font-semibold text-ink">{t('pos.emptyMenu')}</h2>
            <p className="mx-auto mt-1.5 max-w-xs text-sm text-ink-3">{t('pos.emptyMenuHint')}</p>
            <Button className="mt-5" onClick={() => seed.mutate()} disabled={seed.isPending}>
              {seed.isPending ? <Spinner /> : null} {t('pos.loadSample')}
            </Button>
          </Card>
        ) : (
          <div>
            {/* Category tab bar — shown when there are backend categories */}
            {categoryOptions.length > 1 ? (
              <div className="mb-5 overflow-x-auto">
                <Segmented
                  options={categoryOptions}
                  value={resolvedCategoryId}
                  onChange={(v) => setActiveCategoryId(v)}
                  ariaLabel={t('pos.categories')}
                />
              </div>
            ) : null}

            {/* Item grid */}
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
              {visibleItems.map((item) => (
                <ItemCard
                  key={item.id}
                  item={item}
                  qty={cartQtyFor(cart, item.id)}
                  locale={locale}
                  onAdd={() => handleItemTap(item)}
                />
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Cart rail */}
      <div className="lg:sticky lg:top-24 lg:self-start">
        <Card className="overflow-hidden">
          <div className="flex items-center justify-between border-b border-line px-5 py-3.5">
            <div className="flex items-center gap-2">
              <h2 className="font-display text-lg font-semibold text-ink">{t('pos.cart')}</h2>
              {resumedOrder ? (
                <Badge tone="amber" className="text-[10px] px-1.5 py-0">
                  {t('pos.parked.resuming')}
                </Badge>
              ) : null}
            </div>
            {lineCount > 0 ? <Badge tone="emerald">{lineCount}</Badge> : null}
          </div>

          {/* Order type + table summary in cart */}
          {lineCount > 0 ? (
            <div className="flex items-center gap-2 border-b border-line px-5 py-2 text-xs text-ink-3">
              <span className="font-medium text-ink-2">{t(orderTypeOptions.find(o => o.value === orderType)?.label ? `pos.orderType.${orderType === 'DINE_IN' ? 'dineIn' : orderType === 'TAKEAWAY' ? 'takeaway' : 'delivery'}` : 'pos.orderType.dineIn')}</span>
              {orderType === 'DINE_IN' && selectedTable ? (
                <>
                  <span>·</span>
                  <Table2 className="size-3" aria-hidden="true" />
                  <span>{selectedTable.label}</span>
                </>
              ) : null}
              {orderType === 'DINE_IN' && !selectedTable ? (
                <span className="italic">{t('pos.table.noTableSelected')}</span>
              ) : null}
            </div>
          ) : null}

          {lineCount === 0 ? (
            <p className="px-5 py-10 text-center text-sm text-ink-3">{t('pos.cartEmpty')}</p>
          ) : (
            <>
              {/* Line items */}
              <ul className="divide-y divide-line">
                {cart.map((line, idx) => {
                  const item = items.find((i) => i.id === line.menuItemId)
                  if (!item) return null
                  return (
                    <li key={`${line.menuItemId}-${idx}`} className="px-5 py-3">
                      <div className="flex items-center gap-3">
                        <div className="min-w-0 flex-1">
                          <div className="truncate text-sm font-medium text-ink">{item.name}</div>
                          <div className="tnum mt-0.5 font-mono text-xs text-ink-3">
                            {formatMoney(line.effectiveUnitPriceMinor, item.currency, locale)}
                          </div>
                        </div>
                        <div className="flex items-center gap-1.5">
                          <button
                            type="button"
                            aria-label={t('pos.decreaseQty', { name: item.name })}
                            onClick={() => decCartLine(idx)}
                            className="grid size-7 place-items-center rounded-md border border-line-strong text-ink-2 hover:bg-paper"
                          >
                            {line.qty === 1 ? <Trash2 className="size-3.5" /> : <Minus className="size-3.5" />}
                          </button>
                          <span className="tnum w-5 text-center font-mono text-sm text-ink">{line.qty}</span>
                          <button
                            type="button"
                            aria-label={t('pos.increaseQty', { name: item.name })}
                            onClick={() => handleItemTap(item)}
                            className="grid size-7 place-items-center rounded-md border border-line-strong text-ink-2 hover:bg-paper"
                          >
                            <Plus className="size-3.5" />
                          </button>
                        </div>
                      </div>
                      {/* Modifier names under the line */}
                      {line.selectedOptionNames.length > 0 ? (
                        <div className="mt-1 pl-0 flex flex-wrap gap-1">
                          {line.selectedOptionNames.map((name) => (
                            <span
                              key={name}
                              className="text-[11px] text-ink-3 leading-tight"
                            >
                              {name}
                            </span>
                          ))}
                        </div>
                      ) : null}
                    </li>
                  )
                })}
              </ul>

              {/* Discount input */}
              <div className="border-t border-line px-5 py-3">
                <label htmlFor="pos-discount" className="block text-xs font-medium text-ink-3 mb-1.5">
                  {t('pos.addDiscount')}
                </label>
                <input
                  id="pos-discount"
                  type="number"
                  inputMode="decimal"
                  min="0"
                  step="1"
                  value={discountInput}
                  onChange={(e) => handleDiscountChange(e.target.value)}
                  placeholder="0"
                  aria-describedby={discountError ? 'pos-discount-error' : undefined}
                  className={cn(
                    'w-full rounded-lg border bg-surface px-3 py-2 font-mono text-sm text-ink tnum',
                    'transition-colors placeholder:text-ink-3/50',
                    'focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/10',
                    discountError ? 'border-rose' : 'border-line-strong',
                  )}
                />
                {discountError ? (
                  <p id="pos-discount-error" className="mt-1 text-xs text-rose" role="alert">
                    {discountError}
                  </p>
                ) : null}
              </div>

              {/* Price breakdown */}
              <div className="border-t border-line px-5 py-4">
                <PriceBreakdown
                  breakdown={breakdown}
                  isLoading={quoteQuery.isFetching && !quoteQuery.isPlaceholderData}
                  currency={currency}
                  locale={locale}
                  clientSubtotalMinor={clientSubtotalMinor}
                />
              </div>
            </>
          )}

          {/* Action buttons: Hold + Charge */}
          <div className={cn('px-5 py-4 flex gap-2', lineCount > 0 ? 'border-t border-line' : '')}>
            {/* Hold button — only when cart is non-empty and not already resuming (avoid double-park) */}
            {lineCount > 0 && !resumedOrder ? (
              <Button
                variant="outline"
                className="flex-none"
                disabled={lineCount === 0 || parkOrder.isPending}
                onClick={handleHold}
                aria-label={t('pos.parked.hold')}
                title={t('pos.parked.hold')}
              >
                {parkOrder.isPending ? <Spinner /> : <PauseCircle className="size-4" />}
                {t('pos.parked.hold')}
              </Button>
            ) : null}

            <Button
              className="w-full flex-1"
              disabled={lineCount === 0 || !!discountError || resumeQuery.isLoading}
              onClick={openPayment}
            >
              {resumeQuery.isLoading ? (
                <Spinner />
              ) : (
                <>
                  {t('pos.charge')} · {formatMoney(grandTotalMinor, currency, locale)}
                </>
              )}
            </Button>
          </div>

          {/* Park error */}
          {parkOrder.isError ? (
            <p className="px-5 pb-3 text-xs text-rose" role="alert">
              {(parkOrder.error as Error).message}
            </p>
          ) : null}

          {/* Park success toast */}
          {parkOrder.isSuccess && lineCount === 0 ? (
            <p className="px-5 pb-3 text-xs text-emerald-2" role="status">
              {t('pos.parked.holdSuccess')}
            </p>
          ) : null}
        </Card>
      </div>

      {/* Modifier picker modal */}
      {modifierItem ? (
        <ModifierModal
          item={modifierItem}
          locale={locale}
          onConfirm={handleModifierConfirm}
          onClose={() => setModifierItem(null)}
        />
      ) : null}

      {/* Payment modal */}
      {modal === 'payment' ? (
        <PaymentModal
          session={session}
          lines={cartLines}
          breakdown={breakdown}
          grandTotalMinor={grandTotalMinor}
          discountMinor={discountMinor}
          currency={currency}
          locale={locale}
          onSuccess={handlePaymentSuccess}
          onClose={() => setModal(null)}
          parkedOrderId={resumedOrder?.orderId ?? null}
          orderType={orderType}
          tableId={orderType === 'DINE_IN' ? selectedTableId : null}
        />
      ) : null}

      {/* Receipt overlay */}
      {modal === 'receipt' && placedOrder && placedPayment ? (
        <ReceiptView
          order={placedOrder}
          payment={placedPayment}
          locale={locale}
          businessName={session.name}
          onNew={handleNewOrder}
        />
      ) : null}

      {/* Table management panel */}
      {showTableMgmt ? (
        <TableManagement
          session={session}
          onClose={() => setShowTableMgmt(false)}
        />
      ) : null}

      {/* Parked orders tray */}
      {showParkedTray ? (
        <ParkedTray
          session={session}
          locale={locale}
          onResume={handleResume}
          onClose={() => setShowParkedTray(false)}
        />
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// TablePicker
// ---------------------------------------------------------------------------

function TablePicker({
  tables,
  selectedTableId,
  onSelect,
  isLoading,
}: {
  tables: TableResponse[]
  selectedTableId: string | null
  onSelect: (tableId: string | null) => void
  isLoading: boolean
}) {
  const { t } = useTranslation()

  if (isLoading) {
    return (
      <div className="mt-3 flex items-center gap-2 text-xs text-ink-3">
        <Spinner />
        {t('common.loading')}
      </div>
    )
  }

  if (tables.length === 0) {
    return (
      <p className="mt-3 text-xs text-ink-3 italic">
        {t('pos.table.noTables')} — {t('pos.table.noTableHint')}
      </p>
    )
  }

  return (
    <div className="mt-3" role="group" aria-label={t('pos.table.selectTable')}>
      <p className="mb-2 text-xs font-medium text-ink-3">{t('pos.table.selectTable')}</p>
      <div className="flex flex-wrap gap-2">
        {/* "No table" option */}
        <button
          type="button"
          onClick={() => onSelect(null)}
          aria-pressed={selectedTableId === null}
          className={cn(
            'rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors',
            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
            selectedTableId === null
              ? 'border-emerald bg-emerald-tint text-emerald-2'
              : 'border-line-strong bg-surface text-ink-2 hover:border-ink-3',
          )}
        >
          {t('pos.table.noTable')}
        </button>

        {tables.map((tbl) => (
          <button
            key={tbl.tableId}
            type="button"
            disabled={tbl.occupied && selectedTableId !== tbl.tableId}
            onClick={() => onSelect(tbl.tableId)}
            aria-pressed={selectedTableId === tbl.tableId}
            aria-label={
              tbl.occupied
                ? t('pos.table.occupiedLabel', { label: tbl.label })
                : t('pos.table.selectLabel', { label: tbl.label })
            }
            className={cn(
              'relative rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors',
              'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
              tbl.occupied && selectedTableId !== tbl.tableId
                ? 'cursor-not-allowed border-line bg-paper opacity-50 text-ink-3'
                : selectedTableId === tbl.tableId
                ? 'border-emerald bg-emerald-tint text-emerald-2'
                : 'border-line-strong bg-surface text-ink-2 hover:border-ink-3',
            )}
          >
            {tbl.label}
            {tbl.occupied ? (
              <span className="absolute -right-1 -top-1 block size-2 rounded-full bg-amber" aria-hidden="true" />
            ) : null}
          </button>
        ))}
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// PriceBreakdown sub-component
// ---------------------------------------------------------------------------

function PriceBreakdown({
  breakdown,
  isLoading,
  currency,
  locale,
  clientSubtotalMinor,
}: {
  breakdown: PriceBreakdownResponse | null
  isLoading: boolean
  currency: string
  locale: string
  clientSubtotalMinor: number
}) {
  const { t } = useTranslation()

  if (!breakdown) {
    return (
      <div className="flex items-baseline justify-between">
        <span className="text-sm text-ink-3">{t('pos.subtotal')}</span>
        <span className="tnum font-mono text-sm text-ink">
          {isLoading ? (
            <span className="inline-block h-3.5 w-16 animate-pulse rounded bg-paper" />
          ) : (
            formatMoney(clientSubtotalMinor, currency, locale)
          )}
        </span>
      </div>
    )
  }

  const illustrative = breakdown.usesIllustrativeRules

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
          <span className="tnum font-mono text-rose">
            − {formatMoney(breakdown.discountMinor, currency, locale)}
          </span>
        </div>
      ) : null}

      <div className="flex items-center justify-between">
        <span className="flex items-center gap-1.5 text-ink-3">
          {t('pos.serviceCharge')}
          {illustrative ? <EstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono text-ink">
          {formatMoney(breakdown.serviceChargeMinor, currency, locale)}
        </span>
      </div>

      <div className="flex items-center justify-between">
        <span className="flex items-center gap-1.5 text-ink-3">
          {t('pos.tax')}
          {illustrative ? <EstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono text-ink">
          {formatMoney(breakdown.taxMinor, currency, locale)}
        </span>
      </div>

      <div className="flex items-baseline justify-between border-t border-line pt-2 mt-1">
        <span className="font-medium text-ink">{t('pos.total')}</span>
        <span className="tnum font-mono text-xl font-medium text-ink">
          {isLoading ? (
            <span className="inline-block h-5 w-20 animate-pulse rounded bg-paper" />
          ) : (
            formatMoney(breakdown.grandTotalMinor, currency, locale)
          )}
        </span>
      </div>
    </div>
  )
}

function EstimatedBadge({ hint }: { hint: string }) {
  const { t } = useTranslation()
  return (
    <span title={hint} aria-label={hint} className="inline-flex items-center gap-0.5">
      <Badge tone="amber" className="text-[10px] py-0 px-1.5">
        {t('pos.estimated')}
      </Badge>
      <Info className="size-3 text-amber-2/70" aria-hidden="true" />
    </span>
  )
}

// ---------------------------------------------------------------------------
// Item card
// ---------------------------------------------------------------------------

function ItemCard({
  item,
  qty,
  locale,
  onAdd,
}: {
  item: MenuItem
  qty: number
  locale: string
  onAdd: () => void
}) {
  const { t } = useTranslation()
  const unavailable = !item.available

  return (
    <button
      type="button"
      onClick={unavailable ? undefined : onAdd}
      disabled={unavailable}
      aria-label={
        unavailable
          ? t('pos.soldOutLabel', { name: item.name })
          : t('pos.addItem', { name: item.name })
      }
      aria-disabled={unavailable}
      className={cn(
        'relative flex flex-col items-start rounded-card border bg-surface p-4 text-left transition-all',
        unavailable
          ? 'cursor-not-allowed opacity-50'
          : [
              'hover:-translate-y-0.5 hover:border-emerald/40 hover:shadow-[0_10px_30px_-18px_rgba(13,106,74,0.5)]',
              qty > 0 ? 'border-emerald ring-1 ring-emerald/20' : 'border-line',
            ],
        !unavailable && qty > 0 ? 'border-emerald ring-1 ring-emerald/20' : !unavailable ? 'border-line' : 'border-line',
      )}
    >
      {!unavailable && qty > 0 ? (
        <span className="tnum absolute right-3 top-3 grid size-6 place-items-center rounded-full bg-emerald font-mono text-xs font-medium text-white">
          {qty}
        </span>
      ) : null}

      {unavailable ? (
        <span className="absolute right-3 top-3">
          <Badge tone="neutral" className="text-[10px] px-1.5 py-0">
            {t('pos.soldOut')}
          </Badge>
        </span>
      ) : null}

      <span className={cn('font-medium', unavailable ? 'text-ink-3' : 'text-ink')}>{item.name}</span>
      <span className={cn('tnum mt-2 font-mono text-sm', unavailable ? 'text-ink-3/50' : 'text-emerald-2')}>
        {formatMoney(item.priceMinor, item.currency, locale)}
      </span>

      {item.modifierGroups.length > 0 && !unavailable ? (
        <span className="mt-1.5 text-[11px] text-ink-3">{t('pos.hasOptions')}</span>
      ) : null}
    </button>
  )
}

function NoCompany() {
  const { t } = useTranslation()
  return (
    <Card className="mx-auto max-w-md p-10 text-center">
      <h2 className="font-display text-xl font-semibold text-ink">{t('dashboard.noCompany')}</h2>
      <p className="mt-2 text-sm text-ink-3">{t('pos.noCompanyHint')}</p>
      <Link
        to="/onboarding"
        className="mt-5 inline-block rounded-lg bg-emerald px-4 py-2.5 text-sm font-medium text-white hover:bg-emerald-2"
      >
        {t('nav.onboarding')}
      </Link>
    </Card>
  )
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function parseDiscountInput(input: string, currency: string): number {
  if (!input || input.trim() === '') return 0
  const major = Number(input)
  if (isNaN(major) || major < 0) return 0
  const exp = isoMinorExponent(currency)
  return Math.round(major * 10 ** exp)
}

/**
 * A virtual category row: may be a real backend category (with a UUID id) or a synthetic
 * fallback built from the item.category string (legacyKey).
 */
interface VirtualCategory {
  id: string
  name: string
  /** The raw category string key used by legacy items not yet linked to a backend category. */
  legacyKey: string
}

/**
 * Derive an ordered list of virtual categories for the tab bar.
 *
 * Priority:
 * 1. Active backend categories ordered by displayOrder.
 * 2. Fallback: unique item.category strings for items not covered by any backend category,
 *    appended after backend categories.
 */
function deriveCategories(
  items: MenuItem[],
  backendCategories: CategoryResponse[],
): VirtualCategory[] {
  const result: VirtualCategory[] = backendCategories.map((c) => ({
    id: c.id,
    name: c.name,
    legacyKey: c.name.toLowerCase(),
  }))

  // Find items whose categoryId doesn't match any backend category
  const backendIds = new Set(backendCategories.map((c) => c.id))
  const legacyKeys = new Set<string>()
  for (const item of items) {
    if (!item.categoryId || !backendIds.has(item.categoryId)) {
      if (item.category && !legacyKeys.has(item.category)) {
        legacyKeys.add(item.category)
        // Check if a backend category already covers this key
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

/**
 * Sum qty across all cart lines for a given menuItemId (supports multiple lines with
 * different modifier selections for the same item).
 */
function cartQtyFor(cart: CartLine[], menuItemId: string): number {
  return cart.filter((l) => l.menuItemId === menuItemId).reduce((s, l) => s + l.qty, 0)
}

/**
 * A stable string key for a cart line — used to find existing lines when adding.
 * Items with identical menuItemId + sorted selectedOptionIds are merged.
 */
function lineKey(menuItemId: string, selectedOptionIds: string[]): string {
  return `${menuItemId}::${[...selectedOptionIds].sort().join(',')}`
}
