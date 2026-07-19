import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import {
  ArrowLeft,
  Minus,
  Moon,
  Plus,
  Sun,
  Trash2,
  Utensils,
  Info,
  PauseCircle,
  ClipboardList,
  Table2,
  Settings,
} from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Segmented } from '@/components/ui/Segmented'
import { useSession, type CompanySession } from '@/lib/session'
import { useTheme } from '@/lib/theme'
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
  const { theme, toggle } = useTheme()
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
    <div className="min-h-screen bg-paper lg:grid lg:place-items-center lg:p-6 lg:[background:radial-gradient(120%_120%_at_50%_-10%,var(--color-hover),var(--color-paper))]">
      {/* On large screens the POS sits in a centered tablet frame (matching the design comp);
          on smaller/touch screens it renders fullscreen, where a bezel would only waste space. */}
      <div className="flex min-h-screen w-full flex-col lg:h-[calc(100vh-3rem)] lg:max-h-[880px] lg:min-h-0 lg:w-[1280px] lg:max-w-full lg:rounded-[34px] lg:bg-[#0c0e11] lg:p-3 lg:shadow-lg">
        <div className="flex min-h-screen flex-col bg-paper lg:h-full lg:min-h-0 lg:overflow-hidden lg:rounded-[24px]">
      {/* POS header (the front-office chrome — this surface renders outside the back-office shell) */}
      <header className="sticky top-0 z-10 flex flex-wrap items-center gap-3 border-b border-line bg-surface px-4 py-3 sm:px-5">
        <Link
          to="/"
          aria-label={t('a11y.backToDashboard')}
          title={t('a11y.backToDashboard')}
          className="grid size-[38px] shrink-0 place-items-center rounded-xl border border-line text-ink-3 transition-colors hover:bg-hover hover:text-ink"
        >
          <ArrowLeft className="size-[18px]" />
        </Link>
        <div className="min-w-0">
          <div className="font-display text-[17px] font-bold leading-tight tracking-[-0.01em] text-ink">
            {t('pos.title')}
          </div>
          <div className="truncate text-xs text-ink-3">{session.name}</div>
        </div>

        <div className="flex-1" />

        <Segmented
          options={orderTypeOptions}
          value={orderType}
          onChange={handleOrderTypeChange}
          ariaLabel={t('pos.orderType.label')}
        />

        <button
          type="button"
          onClick={() => setShowParkedTray(true)}
          aria-label={t('pos.parked.trayTitle')}
          className="relative inline-flex shrink-0 items-center gap-1.5 rounded-xl border border-line bg-surface px-3 py-2 text-sm font-semibold text-ink-2 transition-colors hover:bg-hover"
        >
          <ClipboardList className="size-4" aria-hidden="true" />
          <span className="hidden sm:inline">{t('pos.parked.parkedLabel')}</span>
          {parkedCount > 0 ? (
            <span className="grid h-[18px] min-w-[18px] place-items-center rounded-full bg-brand-500 px-1.5 text-[11px] font-bold text-white">
              {parkedCount}
            </span>
          ) : null}
        </button>

        <button
          type="button"
          onClick={toggle}
          aria-label={t('a11y.toggleTheme')}
          title={t('a11y.toggleTheme')}
          className="grid size-[38px] shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-colors hover:bg-hover hover:text-ink"
        >
          {theme === 'dark' ? <Sun className="size-[18px]" /> : <Moon className="size-[18px]" />}
        </button>
      </header>

      {/* Body: menu (scrolls) + order rail (fixed) */}
      <div className="grid min-h-0 flex-1 grid-rows-[auto_minmax(0,1fr)] lg:grid-cols-[1fr_380px] lg:grid-rows-1">
        {/* Menu side */}
        <div className="min-h-0 overflow-y-auto px-4 py-5 sm:px-5 lg:px-6">
          {/* Table picker — shown when DINE_IN */}
          {orderType === 'DINE_IN' ? (
            <div className="mb-5">
              <div className="mb-2 flex items-center justify-end">
                <button
                  type="button"
                  onClick={() => setShowTableMgmt(true)}
                  aria-label={t('pos.table.management')}
                  className="inline-flex items-center gap-1 rounded-lg border border-line bg-surface px-2.5 py-1.5 text-xs font-medium text-ink-2 transition-colors hover:bg-hover"
                >
                  <Settings className="size-3.5" aria-hidden="true" />
                  {t('pos.table.management')}
                </button>
              </div>
              <TablePicker
                tables={tables}
                selectedTableId={selectedTableId}
                onSelect={setSelectedTableId}
                isLoading={tablesQuery.isLoading}
              />
            </div>
          ) : null}

          {menuQuery.isLoading ? (
            <div className="grid place-items-center py-24 text-brand-500">
              <Spinner />
            </div>
          ) : items.length === 0 ? (
            <Card className="mx-auto max-w-md p-10 text-center">
              <div className="mx-auto grid size-12 place-items-center rounded-full bg-brand-50 text-brand-600">
                <Utensils className="size-6" />
              </div>
              <h2 className="mt-4 font-display text-xl font-semibold text-ink">
                {t('pos.emptyMenu')}
              </h2>
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

        {/* Order rail */}
        <aside className="flex min-h-0 flex-col bg-surface max-lg:border-t lg:border-l border-line">
          <div className="flex items-center justify-between border-b border-line px-5 py-3.5">
            <div className="flex items-center gap-2">
              <h2 className="font-display text-lg font-semibold text-ink">{t('pos.cart')}</h2>
              {resumedOrder ? (
                <Badge tone="amber" className="px-1.5 py-0 text-[10px]">
                  {t('pos.parked.resuming')}
                </Badge>
              ) : null}
            </div>
            {lineCount > 0 ? <Badge tone="emerald">{lineCount}</Badge> : null}
          </div>

          {/* Order type + table summary in cart */}
          {lineCount > 0 ? (
            <div className="flex items-center gap-2 border-b border-line px-5 py-2 text-xs text-ink-3">
              <span className="font-medium text-ink-2">
                {t(
                  `pos.orderType.${orderType === 'DINE_IN' ? 'dineIn' : orderType === 'TAKEAWAY' ? 'takeaway' : 'delivery'}`,
                )}
              </span>
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

          {/* Lines (scrollable) */}
          <div className="min-h-0 flex-1 overflow-y-auto">
            {lineCount === 0 ? (
              <p className="px-5 py-10 text-center text-sm text-ink-3">{t('pos.cartEmpty')}</p>
            ) : (
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
                            className="grid size-7 place-items-center rounded-lg border border-line text-ink-2 transition-colors hover:bg-hover"
                          >
                            {line.qty === 1 ? (
                              <Trash2 className="size-3.5" />
                            ) : (
                              <Minus className="size-3.5" />
                            )}
                          </button>
                          <span className="tnum w-5 text-center font-mono text-sm text-ink">
                            {line.qty}
                          </span>
                          <button
                            type="button"
                            aria-label={t('pos.increaseQty', { name: item.name })}
                            onClick={() => handleItemTap(item)}
                            className="grid size-7 place-items-center rounded-lg border border-line text-ink-2 transition-colors hover:bg-hover"
                          >
                            <Plus className="size-3.5" />
                          </button>
                        </div>
                      </div>
                      {/* Modifier names under the line */}
                      {line.selectedOptionNames.length > 0 ? (
                        <div className="mt-1 flex flex-wrap gap-1 pl-0">
                          {line.selectedOptionNames.map((name) => (
                            <span key={name} className="text-[11px] leading-tight text-ink-3">
                              {name}
                            </span>
                          ))}
                        </div>
                      ) : null}
                    </li>
                  )
                })}
              </ul>
            )}
          </div>

          {/* Footer: discount + breakdown + actions (pinned) */}
          <div className="border-t border-line">
            {lineCount > 0 ? (
              <>
                <div className="px-5 py-3">
                  <label
                    htmlFor="pos-discount"
                    className="mb-1.5 block text-xs font-medium text-ink-3"
                  >
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
                      'tnum w-full rounded-xl border bg-surface px-3 py-2 font-mono text-sm text-ink',
                      'transition-colors placeholder:text-ink-3/50',
                      'focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-500/12',
                      discountError ? 'border-loss' : 'border-line',
                    )}
                  />
                  {discountError ? (
                    <p id="pos-discount-error" className="mt-1 text-xs text-loss" role="alert">
                      {discountError}
                    </p>
                  ) : null}
                </div>

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
            ) : null}

            <div className={cn('flex gap-2 px-5 py-4', lineCount > 0 ? 'border-t border-line' : '')}>
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
              <p className="px-5 pb-3 text-xs text-loss" role="alert">
                {(parkOrder.error as Error).message}
              </p>
            ) : null}

            {/* Park success toast */}
            {parkOrder.isSuccess && lineCount === 0 ? (
              <p className="px-5 pb-3 text-xs text-brand-700" role="status">
                {t('pos.parked.holdSuccess')}
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
          tableLabel={
            placedOrder.tableId
              ? (tablesQuery.data ?? []).find((tbl) => tbl.tableId === placedOrder.tableId)?.label ?? null
              : null
          }
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
      </div>
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
    <div role="group" aria-label={t('pos.table.selectTable')}>
      <p className="mb-2 text-[11px] font-bold uppercase tracking-[0.05em] text-ink-3">
        {t('pos.table.selectTable')}
      </p>
      <div className="flex flex-wrap gap-2.5">
        {/* "No table" option */}
        <button
          type="button"
          onClick={() => onSelect(null)}
          aria-pressed={selectedTableId === null}
          className={cn(
            'w-[74px] rounded-[13px] border-[1.5px] py-2.5 text-center transition-colors',
            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
            selectedTableId === null
              ? 'border-brand-500 bg-brand-500'
              : 'border-line bg-surface hover:bg-hover',
          )}
        >
          <div
            className={cn(
              'text-[15px] font-bold',
              selectedTableId === null ? 'text-white' : 'text-ink',
            )}
          >
            —
          </div>
          <div
            className={cn(
              'mt-0.5 text-[10.5px]',
              selectedTableId === null ? 'text-white/80' : 'text-ink-3',
            )}
          >
            {t('pos.table.noTable')}
          </div>
        </button>

        {tables.map((tbl) => {
          const selected = selectedTableId === tbl.tableId
          const blocked = tbl.occupied && !selected
          return (
            <button
              key={tbl.tableId}
              type="button"
              disabled={blocked}
              onClick={() => onSelect(tbl.tableId)}
              aria-pressed={selected}
              aria-label={
                tbl.occupied
                  ? t('pos.table.occupiedLabel', { label: tbl.label })
                  : t('pos.table.selectLabel', { label: tbl.label })
              }
              className={cn(
                'w-[74px] rounded-[13px] border-[1.5px] py-2.5 text-center transition-colors',
                'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
                selected
                  ? 'border-brand-500 bg-brand-500'
                  : blocked
                    ? 'cursor-not-allowed border-line bg-paper opacity-60'
                    : 'border-line bg-surface hover:bg-hover',
              )}
            >
              <div
                className={cn(
                  'text-[15px] font-bold',
                  selected ? 'text-white' : blocked ? 'text-ink-3' : 'text-ink',
                )}
              >
                {tbl.label}
              </div>
              <div
                className={cn(
                  'mt-0.5 text-[10.5px]',
                  selected ? 'text-white/80' : 'text-ink-3',
                )}
              >
                {t('pos.table.capacity', { n: tbl.capacity })}
              </div>
            </button>
          )
        })}
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
            <span className="inline-block h-3.5 w-16 animate-pulse rounded bg-ink-100" />
          ) : (
            formatMoney(clientSubtotalMinor, currency, locale)
          )}
        </span>
      </div>
    )
  }

  const illustrative = breakdown.usesIllustrativeRules
  // Effective rates derived from the amounts the server returned — the rate itself isn't in the
  // response, so we surface what was actually applied rather than a hardcoded figure.
  const rateBase = breakdown.subtotalMinor - breakdown.discountMinor
  const serviceRate = rateBase > 0 ? breakdown.serviceChargeMinor / rateBase : null
  const taxBase = rateBase + breakdown.serviceChargeMinor
  const taxRate = taxBase > 0 ? breakdown.taxMinor / taxBase : null

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

      <div className="flex items-center justify-between">
        <span className="flex items-center gap-1.5 text-ink-3">
          {t('pos.serviceCharge')}
          {serviceRate != null ? <RateChip rate={serviceRate} locale={locale} /> : null}
          {illustrative ? <EstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono text-ink">
          {formatMoney(breakdown.serviceChargeMinor, currency, locale)}
        </span>
      </div>

      <div className="flex items-center justify-between">
        <span className="flex items-center gap-1.5 text-ink-3">
          {t('pos.tax')}
          {taxRate != null ? <RateChip rate={taxRate} locale={locale} /> : null}
          {illustrative ? <EstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono text-ink">
          {formatMoney(breakdown.taxMinor, currency, locale)}
        </span>
      </div>

      <div className="mt-1 flex items-baseline justify-between border-t border-line pt-2">
        <span className="font-semibold text-ink">{t('pos.total')}</span>
        <span className="tnum font-mono text-xl font-bold text-ink">
          {isLoading ? (
            <span className="inline-block h-5 w-20 animate-pulse rounded bg-ink-100" />
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

/** The effective rate (e.g. "10%") applied to a service-charge / tax line. */
function RateChip({ rate, locale }: { rate: number; locale: string }) {
  return (
    <span className="tnum rounded-full bg-ink-50 px-1.5 py-0.5 text-[10px] font-semibold text-ink-2">
      {new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 0 }).format(rate)}
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
        'relative flex flex-col items-start rounded-2xl border bg-surface p-4 text-left shadow-sm transition-all',
        unavailable
          ? 'cursor-not-allowed border-line opacity-55'
          : qty > 0
            ? 'border-brand-500 ring-1 ring-brand-500/25 hover:-translate-y-0.5'
            : 'border-line hover:-translate-y-0.5 hover:border-brand-300 hover:shadow-md',
      )}
    >
      {!unavailable && qty > 0 ? (
        <span className="tnum absolute right-3 top-3 grid size-6 place-items-center rounded-full bg-brand-500 font-mono text-xs font-bold text-white">
          {qty}
        </span>
      ) : null}

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
    </button>
  )
}

function NoCompany() {
  const { t } = useTranslation()
  return (
    <div className="grid min-h-screen place-items-center bg-paper px-5">
      <Card className="w-full max-w-md p-10 text-center">
        <h2 className="font-display text-xl font-semibold text-ink">{t('dashboard.noCompany')}</h2>
        <p className="mt-2 text-sm text-ink-3">{t('pos.noCompanyHint')}</p>
        <Link
          to="/onboarding"
          className="mt-5 inline-block rounded-xl bg-brand-500 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-brand-600"
        >
          {t('nav.onboarding')}
        </Link>
      </Card>
    </div>
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
