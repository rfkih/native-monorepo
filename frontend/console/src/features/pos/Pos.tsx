import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import {
  ArrowLeft,
  BookOpen,
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
  ShoppingBag,
  X,
  ImageOff,
  ChevronDown,
  Store,
  ChefHat,
  LogOut,
} from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Segmented } from '@/components/ui/Segmented'
import { useSession, type CompanySession } from '@/lib/session'
import { useAuth } from '@/lib/authContext'
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
import { TableFloor } from './TableFloor'
import { BillDetail } from './BillDetail'
import { useBills } from './billsApi'

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
  const auth = useAuth()
  const locale = localeOf(i18n.language)
  const menuQuery = useMenu(session)
  const categoriesQuery = useCategories(session)
  const tablesQuery = useTables(session)
  const parkedQuery = useParkedOrders(session)
  const billsQuery = useBills(session)
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
  const [showTableFloor, setShowTableFloor] = useState(false)
  // openBillId: when non-null the POS is in "bill mode" — showing BillDetail
  const [openBillId, setOpenBillId] = useState<string | null>(null)
  // Below lg the order rail is a slide-up drawer opened from the sticky "view order" bar.
  const [cartOpen, setCartOpen] = useState(false)

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
  // Count of tables with open bills (derived — not using the API's occupied flag).
  const openBillsList = billsQuery.data ?? []
  const occupiedCount = new Set(
    openBillsList.filter((b) => b.tableId != null).map((b) => b.tableId as string),
  ).size
  // Total open bill count for the badge (shows takeaway + dine-in).
  const billsCount = openBillsList.length

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
    // Guard: combined sold-out rule (manual 86 OR tracked stock at zero)
    if (!item.available || (item.stockQuantity != null && item.stockQuantity <= 0)) return
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
    setCartOpen(false)
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
    <div className="flex h-[100dvh] flex-col overflow-hidden bg-paper">
      {/* ── Header ─────────────────────────────────────────────────────────── */}
      <header className="sticky top-0 z-10 border-b border-line bg-surface/95 backdrop-blur-sm">
        {/* Main row */}
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-3 sm:px-6">
          <Link
            to="/"
            aria-label={t('a11y.backToDashboard')}
            title={t('a11y.backToDashboard')}
            className="grid size-9 shrink-0 place-items-center rounded-xl border border-line text-ink-3 transition-all hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            <ArrowLeft className="size-4" />
          </Link>

          {/* Business identity */}
          <div className="flex min-w-0 flex-1 items-center gap-2.5">
            <span className="grid size-9 shrink-0 place-items-center rounded-xl bg-gradient-to-br from-brand-500 to-brand-700 text-white shadow-sm">
              <Store className="size-[18px]" />
            </span>
            <div className="min-w-0">
              <div className="truncate font-display text-[17px] font-bold leading-tight text-ink">
                {session.name}
              </div>
              <div className="text-[11px] leading-tight text-ink-3">{t('pos.title')}</div>
            </div>
          </div>

          {/* Order type + table — inline from sm; drops to its own row on phones */}
          <div className="order-last flex w-full items-center gap-2 overflow-x-auto sm:order-none sm:w-auto sm:overflow-visible">
            <Segmented
              options={orderTypeOptions}
              value={orderType}
              onChange={handleOrderTypeChange}
              ariaLabel={t('pos.orderType.label')}
            />
            {orderType === 'DINE_IN' ? (
              <TableSelect
                tables={tables}
                selectedTableId={selectedTableId}
                onSelect={setSelectedTableId}
                onManage={() => setShowTableMgmt(true)}
              />
            ) : null}
          </div>

          {/* Action cluster */}
          <div className="flex items-center gap-2">
            {/* Menu link */}
            <Link
              to="/menu"
              aria-label={t('nav.menu')}
              title={t('nav.menu')}
              className="grid size-9 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
            >
              <BookOpen className="size-4" />
            </Link>

            {/* Kitchen display */}
            <Link
              to="/kitchen"
              aria-label={t('nav.kitchen')}
              title={t('nav.kitchen')}
              className="grid size-9 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
            >
              <ChefHat className="size-4" />
            </Link>

            {/* Tables button */}
            <button
              type="button"
              onClick={() => setShowTableFloor(true)}
              aria-label={t('bills.floorTitle')}
              className="relative grid size-9 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
            >
              <Table2 className="size-4" aria-hidden="true" />
              {billsCount > 0 ? (
                <span className="absolute -right-1 -top-1 grid h-[16px] min-w-[16px] place-items-center rounded-full bg-brand-500 px-1 text-[9px] font-bold text-white">
                  {occupiedCount > 0 ? occupiedCount : billsCount}
                </span>
              ) : null}
            </button>

            {/* Parked button */}
            <button
              type="button"
              onClick={() => setShowParkedTray(true)}
              aria-label={t('pos.parked.trayTitle')}
              className="relative grid size-9 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
            >
              <ClipboardList className="size-4" aria-hidden="true" />
              {parkedCount > 0 ? (
                <span className="absolute -right-1 -top-1 grid h-[16px] min-w-[16px] place-items-center rounded-full bg-amber-500 px-1 text-[9px] font-bold text-white">
                  {parkedCount}
                </span>
              ) : null}
            </button>

            {/* Theme toggle */}
            <button
              type="button"
              onClick={toggle}
              aria-label={t('a11y.toggleTheme')}
              title={t('a11y.toggleTheme')}
              className="grid size-9 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700"
            >
              {theme === 'dark' ? <Sun className="size-4" /> : <Moon className="size-4" />}
            </button>

            {/* Logout */}
            <button
              type="button"
              onClick={auth.logout}
              aria-label={t('nav.logout')}
              title={t('nav.logout')}
              className="grid size-9 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-loss/40 hover:bg-tint-loss hover:text-loss focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
            >
              <LogOut className="size-4" />
            </button>
          </div>
        </div>
      </header>

      {/* ── Body ───────────────────────────────────────────────────────────── */}
      <div className="relative flex min-h-0 flex-1 overflow-hidden">
        {/* ── Menu side ─────────────────────────────────────────────────── */}
        <div className="min-h-0 flex-1 overflow-y-auto px-4 py-6 pb-28 sm:px-6 lg:px-8 lg:pb-8">
          {/* ── Menu grid ─────────────────────────────────────────────── */}
          {menuQuery.isLoading ? (
            <MenuSkeleton />
          ) : items.length === 0 ? (
            <EmptyMenu onLoad={() => seed.mutate()} isLoading={seed.isPending} />
          ) : (
            <div>
              {/* Category pill bar */}
              {categoryOptions.length > 1 ? (
                <CategoryBar
                  options={categoryOptions}
                  value={resolvedCategoryId}
                  onChange={(v) => setActiveCategoryId(v)}
                />
              ) : null}

              {/* Item grid — 2-up → 3-up → 4-up (Square-style: fewer, larger tiles) */}
              <div className="grid grid-cols-2 gap-4 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4">
                {visibleItems.map((item, idx) => (
                  <ItemCard
                    key={item.id}
                    item={item}
                    qty={cartQtyFor(cart, item.id)}
                    locale={locale}
                    index={idx}
                    onAdd={() => handleItemTap(item)}
                  />
                ))}
              </div>
            </div>
          )}
        </div>

        {/* ── Order rail ────────────────────────────────────────────────── */}
        {/* Inline right column on lg+; slide-up drawer below lg */}
        <aside
          className={cn(
            'fixed inset-0 z-40 flex min-h-0 flex-col bg-surface shadow-lg',
            'transition-transform duration-300 ease-out',
            cartOpen ? 'translate-y-0' : 'translate-y-full',
            'lg:static lg:z-auto lg:w-[400px] lg:shrink-0 lg:translate-y-0',
            'lg:border-l lg:border-line lg:shadow-none lg:transition-none xl:w-[440px]',
          )}
        >
          {/* Rail header */}
          <div className="flex items-center justify-between border-b border-line px-5 py-4 sm:px-6">
            <div className="flex items-center gap-2.5">
              <h2 className="font-display text-base font-bold text-ink">{t('pos.cart')}</h2>
              {lineCount > 0 ? (
                <span className="grid h-5 min-w-5 place-items-center rounded-full bg-brand-500 px-1.5 text-[11px] font-bold text-white">
                  {lineCount}
                </span>
              ) : null}
              {resumedOrder ? (
                <Badge tone="amber" className="px-1.5 py-0 text-[10px]">
                  {t('pos.parked.resuming')}
                </Badge>
              ) : null}
            </div>
            <div className="flex items-center gap-2">
              {/* Order type + table chip */}
              {lineCount > 0 ? (
                <span className="inline-flex items-center gap-1 rounded-full border border-line bg-paper px-2 py-0.5 text-[11px] font-medium text-ink-3">
                  {orderType === 'DINE_IN' ? (
                    <Table2 className="size-3" aria-hidden="true" />
                  ) : (
                    <ShoppingBag className="size-3" aria-hidden="true" />
                  )}
                  {t(
                    `pos.orderType.${orderType === 'DINE_IN' ? 'dineIn' : orderType === 'TAKEAWAY' ? 'takeaway' : 'delivery'}`,
                  )}
                  {orderType === 'DINE_IN' && selectedTable ? (
                    <> · {selectedTable.label}</>
                  ) : null}
                </span>
              ) : null}
              <button
                type="button"
                onClick={() => setCartOpen(false)}
                aria-label={t('common.close')}
                className="grid size-8 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-ink lg:hidden"
              >
                <X className="size-4" />
              </button>
            </div>
          </div>

          {/* Lines (scrollable) */}
          <div className="min-h-0 flex-1 overflow-y-auto">
            {lineCount === 0 ? (
              /* Empty cart state */
              <div className="flex flex-col items-center justify-center px-8 py-20 text-center">
                <div className="mb-5 grid size-16 place-items-center rounded-3xl bg-brand-50 text-brand-400">
                  <ShoppingBag className="size-7" aria-hidden="true" />
                </div>
                <p className="font-display text-base font-bold text-ink">{t('pos.cartEmpty')}</p>
                <p className="mt-1.5 text-sm text-ink-3">{t('pos.cartEmptyHint')}</p>
              </div>
            ) : (
              <ul className="divide-y divide-line">
                {cart.map((line, idx) => {
                  const item = items.find((i) => i.id === line.menuItemId)
                  if (!item) return null
                  return (
                    <CartLineRow
                      key={`${line.menuItemId}-${idx}`}
                      line={line}
                      item={item}
                      locale={locale}
                      onIncrease={() => handleItemTap(item)}
                      onDecrease={() => decCartLine(idx)}
                    />
                  )
                })}
              </ul>
            )}
          </div>

          {/* Footer: discount + breakdown + actions (pinned) */}
          <div className="border-t border-line">
            {lineCount > 0 ? (
              <>
                {/* Discount input */}
                <div className="px-5 pt-4 pb-3 sm:px-6">
                  <label
                    htmlFor="pos-discount"
                    className="mb-1.5 block text-[11px] font-semibold uppercase tracking-[0.06em] text-ink-3"
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
                      'tnum w-full rounded-xl border bg-paper px-3 py-2 font-mono text-sm text-ink',
                      'transition-colors placeholder:text-ink-3/40',
                      'focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-500/10',
                      discountError ? 'border-loss' : 'border-line',
                    )}
                  />
                  {discountError ? (
                    <p id="pos-discount-error" className="mt-1 text-xs text-loss" role="alert">
                      {discountError}
                    </p>
                  ) : null}
                </div>

                {/* Breakdown */}
                <div className="border-t border-line px-5 py-4 sm:px-6">
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

            {/* Grand total + charge button */}
            <div className={cn('px-5 pb-5 sm:px-6 sm:pb-6', lineCount > 0 ? 'border-t border-line pt-4' : 'pt-4')}>
              {lineCount > 0 ? (
                <div className="mb-4 flex items-baseline justify-between">
                  <span className="font-display text-sm font-semibold text-ink-2">
                    {t('pos.total')}
                  </span>
                  <span className="tnum font-display text-2xl font-bold text-ink">
                    {formatMoney(grandTotalMinor, currency, locale)}
                  </span>
                </div>
              ) : null}

              {/* Hold button — secondary, only when cart has items and not resuming */}
              {lineCount > 0 && !resumedOrder ? (
                <Button
                  variant="outline"
                  className="mb-2 w-full"
                  disabled={lineCount === 0 || parkOrder.isPending}
                  onClick={handleHold}
                  aria-label={t('pos.parked.hold')}
                  title={t('pos.parked.hold')}
                >
                  {parkOrder.isPending ? <Spinner /> : <PauseCircle className="size-4" />}
                  {t('pos.parked.hold')}
                </Button>
              ) : null}

              {/* Charge — the Square-style focal point: huge, bold, full-width green */}
              <Button
                className="w-full rounded-2xl py-4 text-base font-bold shadow-md"
                disabled={lineCount === 0 || !!discountError || resumeQuery.isLoading}
                onClick={openPayment}
              >
                {resumeQuery.isLoading ? (
                  <Spinner />
                ) : lineCount === 0 ? (
                  t('pos.charge')
                ) : (
                  <>
                    {t('pos.charge')}
                    <span className="mx-1 opacity-60">·</span>
                    {formatMoney(grandTotalMinor, currency, locale)}
                  </>
                )}
              </Button>

              {/* Park error / success */}
              {parkOrder.isError ? (
                <p className="mt-2 text-xs text-loss" role="alert">
                  {(parkOrder.error as Error).message}
                </p>
              ) : null}
              {parkOrder.isSuccess && lineCount === 0 ? (
                <p className="mt-2 text-xs text-brand-700" role="status">
                  {t('pos.parked.holdSuccess')}
                </p>
              ) : null}
            </div>
          </div>
        </aside>
      </div>

      {/* ── Modals ─────────────────────────────────────────────────────────── */}

      {modifierItem ? (
        <ModifierModal
          item={modifierItem}
          locale={locale}
          onConfirm={handleModifierConfirm}
          onClose={() => setModifierItem(null)}
        />
      ) : null}

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

      {showTableMgmt ? (
        <TableManagement session={session} onClose={() => setShowTableMgmt(false)} />
      ) : null}

      {showParkedTray ? (
        <ParkedTray
          session={session}
          locale={locale}
          onResume={handleResume}
          onClose={() => setShowParkedTray(false)}
        />
      ) : null}

      {showTableFloor ? (
        <TableFloor
          session={session}
          locale={locale}
          tables={tables}
          onOpenBill={(billId) => {
            setOpenBillId(billId)
            setShowTableFloor(false)
          }}
          onClose={() => setShowTableFloor(false)}
        />
      ) : null}

      {openBillId ? (
        <BillDetail
          session={session}
          locale={locale}
          billId={openBillId}
          tableLabel={
            (() => {
              const bill = openBillsList.find((b) => b.id === openBillId)
              if (!bill?.tableId) return null
              return tables.find((tbl) => tbl.tableId === bill.tableId)?.label ?? null
            })()
          }
          onBack={() => {
            setOpenBillId(null)
            setShowTableFloor(true)
          }}
          onPaid={() => {
            setOpenBillId(null)
          }}
        />
      ) : null}

      {/* Mobile "view order" sticky bar — big green Square-style button */}
      {lineCount > 0 && !cartOpen ? (
        <div className="fixed inset-x-0 bottom-0 z-30 border-t border-line bg-surface/95 px-4 py-4 shadow-lg backdrop-blur-sm lg:hidden">
          <button
            type="button"
            onClick={() => setCartOpen(true)}
            className="flex w-full items-center gap-3 rounded-2xl bg-brand-500 px-5 py-3.5 text-white shadow-md transition-all hover:bg-brand-600 active:scale-[0.99]"
          >
            <ShoppingBag className="size-5 shrink-0" aria-hidden="true" />
            <span className="flex-1 text-left text-sm font-bold">{t('pos.viewOrder')}</span>
            <span className="tnum rounded-full bg-white/20 px-2.5 py-0.5 text-xs font-bold">
              {lineCount}
            </span>
            <span className="tnum font-display text-base font-bold">
              {formatMoney(grandTotalMinor, currency, locale)}
            </span>
          </button>
        </div>
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// CategoryBar — tactile horizontal pill bar
// ---------------------------------------------------------------------------

function CategoryBar({
  options,
  value,
  onChange,
}: {
  options: { value: string; label: string }[]
  value: string
  onChange: (v: string) => void
}) {
  const { t } = useTranslation()
  return (
    <div
      role="tablist"
      aria-label={t('pos.categories')}
      className="mb-6 flex gap-2.5 overflow-x-auto pb-1 [-ms-overflow-style:none] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
    >
      {options.map((opt) => {
        const active = opt.value === value
        return (
          <button
            key={opt.value}
            type="button"
            role="tab"
            aria-selected={active}
            onClick={() => onChange(opt.value)}
            className={cn(
              'shrink-0 rounded-full px-5 py-2 text-sm font-semibold transition-all',
              'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
              active
                ? 'bg-brand-500 text-white shadow-sm'
                : 'border border-line bg-surface text-ink-2 hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700',
            )}
          >
            {opt.label}
          </button>
        )
      })}
    </div>
  )
}

// ---------------------------------------------------------------------------
// MenuSkeleton — shimmer tile placeholders during initial load
// ---------------------------------------------------------------------------

function MenuSkeleton() {
  return (
    <div className="grid grid-cols-2 gap-4 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4">
      {Array.from({ length: 8 }).map((_, i) => (
        <div
          key={i}
          className="overflow-hidden rounded-2xl border border-line bg-surface shadow-sm"
          aria-hidden="true"
        >
          <div className="shimmer aspect-square w-full" />
          <div className="space-y-2.5 p-4">
            <div className="shimmer h-4 w-3/4 rounded-md" />
            <div className="shimmer h-3.5 w-1/3 rounded-md" />
          </div>
        </div>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// EmptyMenu — polished empty state
// ---------------------------------------------------------------------------

function EmptyMenu({ onLoad, isLoading }: { onLoad: () => void; isLoading: boolean }) {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col items-center justify-center px-6 py-20 text-center">
      <div className="mb-5 grid size-16 place-items-center rounded-2xl bg-brand-50 text-brand-500">
        <Utensils className="size-7" aria-hidden="true" />
      </div>
      <h2 className="font-display text-xl font-bold text-ink">{t('pos.emptyMenu')}</h2>
      <p className="mx-auto mt-2 max-w-xs text-sm text-ink-3">{t('pos.emptyMenuHint')}</p>
      <Button className="mt-6 shadow-sm" onClick={onLoad} disabled={isLoading}>
        {isLoading ? <Spinner /> : null}
        {t('pos.loadSample')}
      </Button>
    </div>
  )
}

// ---------------------------------------------------------------------------
// ItemCard — the centerpiece tile
// ---------------------------------------------------------------------------

function ItemCard({
  item,
  qty,
  locale,
  index,
  onAdd,
}: {
  item: MenuItem
  qty: number
  locale: string
  index: number
  onAdd: () => void
}) {
  const { t } = useTranslation()
  const stockSoldOut = item.stockQuantity != null && item.stockQuantity <= 0
  const unavailable = !item.available || stockSoldOut
  const isLowStock =
    item.stockQuantity != null && item.stockQuantity > 0 && item.stockQuantity <= 5

  // Stagger the entrance animation by tile index (cap at 12 to keep delays reasonable)
  const delayMs = Math.min(index, 12) * 40

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
      style={{ animationDelay: `${delayMs}ms` }}
      className={cn(
        'reveal group relative flex flex-col overflow-hidden rounded-2xl border bg-surface text-left',
        'transition-all duration-200',
        unavailable
          ? 'cursor-not-allowed border-line opacity-55'
          : qty > 0
            ? 'border-brand-500 shadow-md ring-2 ring-brand-500/20 hover:-translate-y-0.5 hover:shadow-lg active:scale-[0.98]'
            : 'border-line shadow-sm hover:-translate-y-0.5 hover:border-brand-300 hover:shadow-md active:scale-[0.98]',
      )}
    >
      {/* ── Photo block — square for bold Square-style tiles ── */}
      <div className="relative aspect-square w-full overflow-hidden bg-brand-50">
        {item.imageUrl ? (
          <img
            src={item.imageUrl}
            alt={item.name}
            loading="lazy"
            className={cn(
              'h-full w-full object-cover transition-transform duration-300',
              !unavailable && 'group-hover:scale-[1.04]',
              unavailable && 'grayscale',
            )}
          />
        ) : (
          /* Tasteful placeholder: brand-tinted gradient + icon */
          <div className="flex h-full w-full items-center justify-center bg-gradient-to-br from-brand-50 to-brand-100">
            <Utensils className="size-10 text-brand-300" aria-hidden="true" />
          </div>
        )}

        {/* Qty badge — top-right, overlapping the photo */}
        {!unavailable && qty > 0 ? (
          <span className="tnum absolute right-2.5 top-2.5 grid h-6 min-w-6 place-items-center rounded-full bg-brand-500 px-1.5 font-mono text-xs font-bold text-white shadow-sm">
            {qty}
          </span>
        ) : null}

        {/* Sold-out overlay chip */}
        {unavailable ? (
          <span className="absolute right-2.5 top-2.5 rounded-full bg-ink/70 px-2 py-0.5 text-[10px] font-semibold text-white backdrop-blur-sm">
            {t('pos.soldOut')}
          </span>
        ) : isLowStock ? (
          <span className="absolute right-2.5 top-2.5 rounded-full bg-amber-500 px-2 py-0.5 text-[10px] font-semibold text-white shadow-sm">
            {t('menu.stock.lowStock', { count: item.stockQuantity })}
          </span>
        ) : null}

        {/* Floating add button — bottom-right of photo, always visible when in cart */}
        {!unavailable ? (
          <span
            aria-hidden="true"
            className={cn(
              'absolute bottom-2.5 right-2.5 grid size-8 place-items-center rounded-full shadow-md transition-all duration-200',
              qty > 0
                ? 'scale-110 bg-brand-500 text-white'
                : 'scale-90 bg-brand-500 text-white opacity-0 group-hover:scale-100 group-hover:opacity-100',
            )}
          >
            <Plus className="size-4" />
          </span>
        ) : null}
      </div>

      {/* ── Content area ── */}
      <div className="flex flex-1 flex-col px-3.5 pb-3.5 pt-3">
        <span
          className={cn(
            'line-clamp-2 text-sm font-semibold leading-snug',
            unavailable ? 'text-ink-3' : 'text-ink',
          )}
        >
          {item.name}
        </span>

        <div className="mt-2 flex items-end justify-between">
          <span
            className={cn(
              'tnum font-display text-[15px] font-bold',
              unavailable ? 'text-ink-3/50' : 'text-brand-700',
            )}
          >
            {formatMoney(item.priceMinor, item.currency, locale)}
          </span>

          {item.modifierGroups.length > 0 && !unavailable ? (
            <span className="rounded-full bg-brand-50 px-1.5 py-0.5 text-[10px] font-semibold text-brand-700">
              {t('pos.hasOptions')}
            </span>
          ) : null}
        </div>
      </div>
    </button>
  )
}

// ---------------------------------------------------------------------------
// CartLineRow
// ---------------------------------------------------------------------------

function CartLineRow({
  line,
  item,
  locale,
  onIncrease,
  onDecrease,
}: {
  line: CartLine
  item: MenuItem
  locale: string
  onIncrease: () => void
  onDecrease: () => void
}) {
  const { t } = useTranslation()
  const lineTotal = line.effectiveUnitPriceMinor * line.qty

  return (
    <li className="px-5 py-4 sm:px-6">
      <div className="flex items-start gap-3.5">
        {/* Item thumbnail — slightly larger for airy feel */}
        <div className="size-12 shrink-0 overflow-hidden rounded-xl border border-line bg-brand-50">
          {item.imageUrl ? (
            <img src={item.imageUrl} alt="" className="h-full w-full object-cover" aria-hidden="true" />
          ) : (
            <div className="flex h-full w-full items-center justify-center">
              <ImageOff className="size-4 text-brand-200" aria-hidden="true" />
            </div>
          )}
        </div>

        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-semibold text-ink">{item.name}</p>
          {/* Modifier names */}
          {line.selectedOptionNames.length > 0 ? (
            <p className="mt-0.5 text-[11px] leading-snug text-ink-3">
              {line.selectedOptionNames.join(', ')}
            </p>
          ) : null}
        </div>

        {/* Line total */}
        <span className="tnum flex-none font-mono text-sm font-semibold text-ink">
          {formatMoney(lineTotal, item.currency, locale)}
        </span>
      </div>

      {/* Qty stepper — inset to align with content */}
      <div className="mt-3 flex items-center gap-2.5 pl-[3.875rem]">
        <button
          type="button"
          aria-label={t('pos.decreaseQty', { name: item.name })}
          onClick={onDecrease}
          className="grid size-8 place-items-center rounded-lg border border-line text-ink-2 transition-colors hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700 focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-500"
        >
          {line.qty === 1 ? (
            <Trash2 className="size-3.5 text-loss" />
          ) : (
            <Minus className="size-3.5" />
          )}
        </button>
        <span className="tnum w-7 text-center font-mono text-sm font-bold text-ink">
          {line.qty}
        </span>
        <button
          type="button"
          aria-label={t('pos.increaseQty', { name: item.name })}
          onClick={onIncrease}
          className="grid size-8 place-items-center rounded-lg border border-brand-500 bg-brand-50 text-brand-600 transition-colors hover:bg-brand-100 focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-brand-500"
        >
          <Plus className="size-3.5" />
        </button>
      </div>
    </li>
  )
}

// ---------------------------------------------------------------------------
// TablePicker
// ---------------------------------------------------------------------------

function TableSelect({
  tables,
  selectedTableId,
  onSelect,
  onManage,
}: {
  tables: TableResponse[]
  selectedTableId: string | null
  onSelect: (tableId: string | null) => void
  onManage: () => void
}) {
  const { t } = useTranslation()
  const active = tables.filter((tb) => tb.active)
  return (
    <div className="flex shrink-0 items-center gap-1.5">
      <div className="relative">
        <Table2
          className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-ink-3"
          aria-hidden="true"
        />
        <select
          value={selectedTableId ?? ''}
          onChange={(e) => onSelect(e.target.value || null)}
          aria-label={t('pos.table.selectTable')}
          className="h-9 max-w-[190px] appearance-none truncate rounded-xl border border-line bg-surface pl-8 pr-8 text-sm font-medium text-ink transition-colors hover:border-brand-300 focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-500/12"
        >
          <option value="">{t('pos.table.noTable')}</option>
          {active.map((tb) => (
            <option
              key={tb.tableId}
              value={tb.tableId}
              disabled={tb.occupied && tb.tableId !== selectedTableId}
            >
              {tb.label} · {t('pos.table.capacity', { n: tb.capacity })}
            </option>
          ))}
        </select>
        <ChevronDown
          className="pointer-events-none absolute right-2.5 top-1/2 size-4 -translate-y-1/2 text-ink-3"
          aria-hidden="true"
        />
      </div>
      <button
        type="button"
        onClick={onManage}
        aria-label={t('pos.table.management')}
        title={t('pos.table.management')}
        className="grid size-9 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
      >
        <Settings className="size-4" aria-hidden="true" />
      </button>
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
            <span className="inline-block h-3.5 w-16 rounded bg-ink-100 shimmer" />
          ) : (
            formatMoney(clientSubtotalMinor, currency, locale)
          )}
        </span>
      </div>
    )
  }

  const illustrative = breakdown.usesIllustrativeRules
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
    </div>
  )
}

function EstimatedBadge({ hint }: { hint: string }) {
  const { t } = useTranslation()
  return (
    <span title={hint} aria-label={hint} className="inline-flex items-center gap-0.5">
      <Badge tone="amber" className="px-1.5 py-0 text-[10px]">
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
// NoCompany
// ---------------------------------------------------------------------------

function NoCompany() {
  const { t } = useTranslation()
  return (
    <div className="grid min-h-screen place-items-center bg-paper px-5">
      <div className="w-full max-w-md rounded-card border border-line bg-surface p-10 text-center shadow-sm">
        <div className="mx-auto mb-4 grid size-14 place-items-center rounded-2xl bg-brand-50 text-brand-500">
          <Utensils className="size-6" aria-hidden="true" />
        </div>
        <h2 className="font-display text-xl font-bold text-ink">{t('dashboard.noCompany')}</h2>
        <p className="mt-2 text-sm text-ink-3">{t('pos.noCompanyHint')}</p>
        <Link
          to="/onboarding"
          className="mt-6 inline-block rounded-xl bg-brand-500 px-5 py-2.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-brand-600"
        >
          {t('nav.onboarding')}
        </Link>
      </div>
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
