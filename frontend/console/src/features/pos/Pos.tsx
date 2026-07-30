/**
 * Pos.tsx — 3b "Bill tabs" redesign.
 *
 * Layout (tablet 768–1179px portrait baseline):
 *   1. Bill tabs bar (~88px, white, border-b)  ← replaces BillsTray slide-in panel
 *   2. Search row (~64px)
 *   3. Left category rail (104px, white, border-r) + Menu grid (flex-1)
 *   4. Bottom summary bar (~96px, white, rounded-t-[28px], strong up-shadow)
 *      → expands to BillDetail sheet overlay
 *
 * Phone <560px:
 *   - Bill tabs → single selector button + dashed new-bill button
 *   - Category rail → horizontal chip row (40px pills)
 *   - Summary bar: one line + Send/Pay buttons
 *   - Modifiers → full-height page (ModifierModal, unchanged)
 *
 * All behaviour, hooks, mutations, and data flows are kept exactly as they were.
 * Only the presentation layer changes.
 */
import { useState, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import {
  ArrowLeft,
  BookOpen,
  ChevronDown,
  ChevronUp,
  ChefHat,
  ClipboardList,
  Gift,
  ImageOff,
  LogOut,
  Moon,
  Plus,
  Send,
  Store,
  Sun,
  Table2,
  Utensils,
  X,
} from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { isOutletNotAssigned } from '@/lib/api'
import { useSession, type CompanySession } from '@/lib/session'
import { useAuth, hasAnyRole } from '@/lib/authContext'
import { useTheme } from '@/lib/theme'
import { localeOf } from '@/i18n'
import { cn } from '@/lib/cn'
import { formatMoney, isoMinorExponent } from '@/lib/money'
import { OutletPicker } from '@/components/OutletPicker'
import { OutletGate } from '@/components/OutletGate'
import { CouponField } from '@/components/CouponField'
import { AppliedPromotionChips } from '@/components/AppliedPromotionChips'
import { MemberField } from '@/components/MemberField'
import { GiftCardSellModal } from '@/components/GiftCardSellModal'
import type { MemberResponse } from '@/features/loyalty/api'
import {
  useMenu,
  useCategories,
  useTables,
  useParkedOrders,
  type MenuItem,
  type CategoryResponse,
  type OrderLineInput,
} from './api'
import { ModifierModal } from './ModifierModal'
import { PaymentModal } from './PaymentModal'
import { ReceiptView } from './ReceiptView'
import { ParkedTray } from './ParkedTray'
import { TableFloor } from './TableFloor'
import { BillDetail } from './BillDetail'
import { useBills, useOpenBill, useAppendLines, type BillSummaryResponse } from './billsApi'
import type { AppliedPromotionResponse, OrderResponse, PaymentResponse } from './api'
import { useQuote, useGetOrder } from './api'

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

type OrderType = 'DINE_IN' | 'TAKEAWAY' | 'DELIVERY'

interface CartLine {
  menuItemId: string
  qty: number
  selectedOptionIds: string[]
  effectiveUnitPriceMinor: number
  selectedOptionNames: string[]
}

// ---------------------------------------------------------------------------
// Root
// ---------------------------------------------------------------------------

export function Pos() {
  const { company } = useSession()
  if (!company) return <NoCompany />
  // The gate resolves a REAL outlet id (never the business-unit id — ADR 0012) and blocks
  // until it has one. key forces a full remount when the effective outlet changes — cart,
  // openBillId, discount, and resume state all reset implicitly, preventing cross-outlet
  // state bleed.
  return (
    <OutletGate company={company} requiredVertical="restaurant">
      {(session) => <PosInner key={session.businessId} session={session} />}
    </OutletGate>
  )
}

// ---------------------------------------------------------------------------
// Inner
// ---------------------------------------------------------------------------

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
  const appendLines = useAppendLines(session)

  // Cart state
  const [cart, setCart] = useState<CartLine[]>([])
  const orderType: OrderType = 'DINE_IN'
  const [discountInput, setDiscountInput] = useState<string>('')
  // Phase 3 (ADR 0026): the committed coupon code fed into the live quote + checkout/pay-parked.
  // Bills (guest tabs) are out of scope for coupons per the ADR — only the walk-in cart uses this.
  const [couponCode, setCouponCode] = useState<string | null>(null)
  // Phase 4 (ADR 0027): the attached loyalty member + committed points redemption, fed into the
  // live quote + checkout/pay-parked. Mirrors the coupon's scope decision — bills (guest tabs) are
  // out of scope; only the walk-in cart attaches a member.
  const [attachedMember, setAttachedMember] = useState<MemberResponse | null>(null)
  const [loyaltyRedeemPoints, setLoyaltyRedeemPoints] = useState<number>(0)
  const [showGiftCardSell, setShowGiftCardSell] = useState(false)
  // The manual discount is owner/manager-only (ADR 0026 §5; the server 403s anyway — this hides the
  // input for a cashier so it never sees an affordance it cannot use).
  const canManualDiscount = hasAnyRole(auth.roles, 'owner', 'manager')

  // Modal / overlay state
  const [modal, setModal] = useState<'payment' | 'receipt' | null>(null)
  const [modifierItem, setModifierItem] = useState<MenuItem | null>(null)
  const [placedOrder, setPlacedOrder] = useState<OrderResponse | null>(null)
  const [placedPayment, setPlacedPayment] = useState<PaymentResponse | null>(null)
  // The applied-promotion detail from the LAST live quote before payment — the checkout/pay-parked
  // response itself carries only the aggregate discount (ADR 0026), so the receipt uses this snapshot.
  const [lastAppliedPromotions, setLastAppliedPromotions] = useState<AppliedPromotionResponse[]>([])
  const [showParkedTray, setShowParkedTray] = useState(false)
  const [showTableFloor, setShowTableFloor] = useState(false)

  // Open bill mode (BillDetail sheet)
  const [openBillId, setOpenBillId] = useState<string | null>(null)
  // Bottom sheet open state (expands the BillDetail sheet from summary bar)
  const [billSheetOpen, setBillSheetOpen] = useState(false)

  // Phone bill selector
  const [showBillSelector, setShowBillSelector] = useState(false)
  // New bill dialog
  const [showOpenBillDialog, setShowOpenBillDialog] = useState(false)

  // Category
  const [activeCategoryId, setActiveCategoryId] = useState<string | null>(null)

  // Search
  const [searchQuery, setSearchQuery] = useState('')

  // Resume parked
  const [resumingOrderId, setResumingOrderId] = useState<string | null>(null)
  const [resumedOrder, setResumedOrder] = useState<OrderResponse | null>(null)
  const resumeQuery = useGetOrder(session, resumingOrderId)
  const [resumeLoaded, setResumeLoaded] = useState(false)

  if (resumeQuery.data && resumingOrderId && !resumeLoaded) {
    const ro = resumeQuery.data
    const rebuiltCart: CartLine[] = ro.lines.map((l) => ({
      menuItemId: l.menuItemId,
      qty: l.qty,
      selectedOptionIds: l.modifiers.map((m) => m.optionId),
      effectiveUnitPriceMinor: l.unitPriceMinor,
      selectedOptionNames: l.modifiers.map((m) => m.nameSnapshot),
    }))
    setCart(rebuiltCart)
    setResumedOrder(ro)
    setResumeLoaded(true)
    setShowParkedTray(false)
  }

  // Data
  const items = menuQuery.data ?? []
  const categories = (categoriesQuery.data ?? []).filter((c) => c.active)
  const tables = (tablesQuery.data ?? []).filter((tbl) => tbl.active)
  const parkedCount = parkedQuery.data?.length ?? 0
  const openBillsList = billsQuery.data ?? []

  const currency = items[0]?.currency ?? session.baseCurrency

  // Category grouping — memoized
  const orderedCategories = useMemo(
    () => deriveCategories(items, categories),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [items, categories],
  )
  const resolvedCategoryId: string = activeCategoryId ?? orderedCategories[0]?.id ?? ''

  // Filtered items — memoized; hoist trimmed lower-case search once
  const searchLower = searchQuery.trim().toLowerCase()
  const visibleItems = useMemo(() => {
    if (searchLower) {
      return items.filter((item) => item.name.toLowerCase().includes(searchLower))
    }
    return items.filter((item) => {
      if (orderedCategories.length === 0) return true
      const cat = orderedCategories.find((c) => c.id === resolvedCategoryId)
      if (!cat) return true
      if (item.categoryId) return item.categoryId === resolvedCategoryId
      return item.category === cat.legacyKey
    })
  }, [items, orderedCategories, resolvedCategoryId, searchLower])

  // Cart helpers — memoized
  const cartLines: OrderLineInput[] = useMemo(
    () =>
      cart.map(({ menuItemId, qty, selectedOptionIds }) => ({
        menuItemId,
        qty,
        selectedOptionIds,
      })),
    [cart],
  )

  // qty-by-menuItemId map for O(1) tile badge lookup
  const cartQtyMap = useMemo<Map<string, number>>(() => {
    const map = new Map<string, number>()
    for (const l of cart) {
      map.set(l.menuItemId, (map.get(l.menuItemId) ?? 0) + l.qty)
    }
    return map
  }, [cart])

  // When a bill is open, use the bill's lines for qty badges on tiles
  const activeBill = openBillId ? openBillsList.find((b) => b.id === openBillId) : null

  const lineCount = cart.reduce((sum, l) => sum + l.qty, 0)
  const discountMinor = parseDiscountInput(discountInput, currency)
  const quoteQuery = useQuote(
    session,
    cartLines,
    discountMinor,
    couponCode,
    attachedMember?.id ?? null,
    loyaltyRedeemPoints,
  )
  const breakdown = quoteQuery.data ?? resumedOrder?.breakdown ?? null
  const clientSubtotalMinor = cart.reduce(
    (sum, l) => sum + l.effectiveUnitPriceMinor * l.qty,
    0,
  )
  const grandTotalMinor =
    breakdown?.grandTotalMinor ?? (resumedOrder?.totalMinor ?? clientSubtotalMinor)
  // The redemption ceiling: the member's balance, capped by the total due BEFORE this redemption
  // (grandTotalMinor already has any currently-committed redemption subtracted — add it back so
  // the cap doesn't shrink itself as points are applied; loyaltyRedeemedMinor is 0 until the quote
  // resolves, which is a safe/conservative starting bound).
  const maxRedeemablePoints = attachedMember
    ? Math.max(
        0,
        Math.min(attachedMember.pointsBalance, grandTotalMinor + (breakdown?.loyaltyRedeemedMinor ?? 0)),
      )
    : 0

  const discountInvalid =
    discountInput !== '' && (isNaN(Number(discountInput)) || Number(discountInput) < 0)

  const activeTableLabel = activeBill?.tableId
    ? (tables.find((tbl) => tbl.tableId === activeBill.tableId)?.label ?? null)
    : null

  const totalBills = openBillsList.length

  // In bill mode, the tile qty badge reflects the open bill's line sums (not the local cart)
  // BillSummaryResponse has lineCount only (no per-item breakdown), so in bill mode
  // we hide per-item counts rather than show stale local-cart counts.
  function tileQty(menuItemId: string): number {
    if (openBillId) return 0 // no per-item count available from summary; hide badge
    return cartQtyMap.get(menuItemId) ?? 0
  }

  // ---------------------------------------------------------------------------
  // Handlers
  // ---------------------------------------------------------------------------

  function handleItemTap(item: MenuItem) {
    if (!item.available || (item.stockQuantity != null && item.stockQuantity <= 0)) return
    if (openBillId) {
      // Bill mode: append directly to the open bill
      if (item.modifierGroups.length > 0) {
        setModifierItem(item)
        return
      }
      appendLines.mutate({
        billId: openBillId,
        lines: [{ menuItemId: item.id, qty: 1, selectedOptionIds: [] }],
      })
      return
    }
    // Walk-in cart mode
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
      return [...prev, { menuItemId, qty: 1, selectedOptionIds, effectiveUnitPriceMinor, selectedOptionNames }]
    })
  }

  function handleModifierConfirm(selectedOptionIds: string[], effectivePriceMinor: number) {
    if (!modifierItem) return
    const names = modifierItem.modifierGroups
      .flatMap((g) => g.options)
      .filter((o) => selectedOptionIds.includes(o.id))
      .map((o) => o.name)
    if (openBillId) {
      // Bill mode: append the modifier-selected item to the open bill
      appendLines.mutate({
        billId: openBillId,
        lines: [{ menuItemId: modifierItem.id, qty: 1, selectedOptionIds }],
      })
      setModifierItem(null)
      return
    }
    // Walk-in cart mode
    addToCart(modifierItem.id, selectedOptionIds, effectivePriceMinor, names)
    setModifierItem(null)
  }

  function clearCart() {
    setCart([])
    setResumingOrderId(null)
    setResumedOrder(null)
    setResumeLoaded(false)
    setCouponCode(null)
    setAttachedMember(null)
    setLoyaltyRedeemPoints(0)
  }

  function handleResume(orderId: string) {
    setResumingOrderId(orderId)
    setResumeLoaded(false)
    setResumedOrder(null)
  }

  function handlePaymentSuccess(order: OrderResponse, payment: PaymentResponse) {
    setPlacedOrder(order)
    setPlacedPayment(payment)
    setLastAppliedPromotions(breakdown?.appliedPromotions ?? [])
    clearCart()
    setModal('receipt')
  }

  function handleNewOrder() {
    setPlacedOrder(null)
    setPlacedPayment(null)
    setModal(null)
  }

  // Open an existing bill from the tabs bar
  function handleTabClick(billId: string) {
    setOpenBillId(billId)
    setBillSheetOpen(false) // collapsed summary bar by default
  }

  // Create a new bill and navigate to it
  function handleBillCreated(billId: string) {
    setShowOpenBillDialog(false)
    setShowBillSelector(false)
    setOpenBillId(billId)
    setBillSheetOpen(false)
  }

  return (
    <div className="flex h-[100dvh] flex-col overflow-hidden bg-paper">

      {/* ── 1. Bill tabs bar (88px) ─────────────────────────────────────────── */}
      <BillTabsBar
        bills={openBillsList}
        activeBillId={openBillId}
        locale={locale}
        onTabClick={handleTabClick}
        onNewBill={() => setShowOpenBillDialog(true)}
        onSelectorClick={() => setShowBillSelector(true)}
      />

      {/* ── 2. Search + utility row (64px) ──────────────────────────────────── */}
      <div className="flex h-16 shrink-0 items-center gap-2.5 border-b border-line bg-surface px-4 sm:px-6">
        {/* Back to dashboard */}
        <Link
          to="/"
          aria-label={t('a11y.backToDashboard')}
          className="grid size-9 shrink-0 place-items-center rounded-xl border border-line text-ink-3 transition-all hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          <ArrowLeft className="size-4" />
        </Link>

        {/* Business identity (md+) */}
        <span className="hidden items-center gap-2 md:flex">
          <span className="grid size-8 shrink-0 place-items-center rounded-xl bg-gradient-to-br from-brand-500 to-brand-700 text-white">
            <Store className="size-[16px]" />
          </span>
          <span className="hidden truncate font-display text-[15px] font-bold leading-tight text-ink lg:block">
            {session.name}
          </span>
        </span>

        {/* Outlet picker — visible when the tenant has ≥1 OUTLET org units */}
        <OutletPicker />

        {/* Search field — flex-1 */}
        <div className="relative flex-1">
          <svg
            className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-ink-3"
            width="17" height="17" viewBox="0 0 24 24" fill="none"
            stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"
            aria-hidden="true"
          >
            <circle cx="11" cy="11" r="8" /><path d="m21 21-4.35-4.35" />
          </svg>
          <input
            type="search"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder={t('bills.searchItems', { count: items.length })}
            aria-label={t('bills.searchItems', { count: items.length })}
            className="h-11 w-full rounded-xl border border-line bg-surface pl-10 pr-4 text-sm text-ink placeholder:text-ink-3 transition-colors focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/10"
          />
        </div>

        {/* Utility buttons cluster */}
        <div className="flex items-center gap-1.5">
          {/* Menu */}
          <Link
            to="/menu"
            aria-label={t('nav.menu')}
            title={t('nav.menu')}
            className="grid size-10 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <BookOpen className="size-4" />
          </Link>
          {/* Kitchen */}
          <Link
            to="/kitchen"
            aria-label={t('nav.kitchen')}
            title={t('nav.kitchen')}
            className="grid size-10 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <ChefHat className="size-4" />
          </Link>
          {/* Table floor */}
          <button
            type="button"
            onClick={() => setShowTableFloor(true)}
            aria-label={t('bills.floorTitle')}
            className="relative grid size-10 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <Table2 className="size-4" aria-hidden="true" />
            {totalBills > 0 ? (
              <span className="absolute -right-1 -top-1 grid h-4 min-w-4 place-items-center rounded-full bg-emerald px-1 text-[9px] font-bold text-on-emerald">
                {totalBills}
              </span>
            ) : null}
          </button>
          {/* Parked */}
          <button
            type="button"
            onClick={() => setShowParkedTray(true)}
            aria-label={t('pos.parked.trayTitle')}
            className="relative grid size-10 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <ClipboardList className="size-4" aria-hidden="true" />
            {parkedCount > 0 ? (
              <span className="absolute -right-1 -top-1 grid h-4 min-w-4 place-items-center rounded-full bg-warning px-1 text-[9px] font-bold text-ink">
                {parkedCount}
              </span>
            ) : null}
          </button>
          {/* Gift card sell — a distinct till action, not a cart line (ADR 0027) */}
          <button
            type="button"
            onClick={() => setShowGiftCardSell(true)}
            aria-label={t('pos.loyalty.giftCard.sellTitle')}
            title={t('pos.loyalty.giftCard.sellTitle')}
            className="grid size-10 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <Gift className="size-4" aria-hidden="true" />
          </button>
          {/* Theme */}
          <button
            type="button"
            onClick={toggle}
            aria-label={t('a11y.toggleTheme')}
            className="grid size-10 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2"
          >
            {theme === 'dark' ? <Sun className="size-4" /> : <Moon className="size-4" />}
          </button>
          {/* Logout */}
          <button
            type="button"
            onClick={auth.logout}
            aria-label={t('nav.logout')}
            className="grid size-10 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-tint-loss hover:bg-tint-loss hover:text-loss focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <LogOut className="size-4" />
          </button>
          {/* Avatar */}
          <span className="grid size-10 shrink-0 place-items-center rounded-full bg-emerald-tint font-semibold text-[13px] text-emerald-2">
            {session.name.slice(0, 2).toUpperCase()}
          </span>
        </div>
      </div>

      {/* ── Body: category rail + menu grid ─────────────────────────────────── */}
      <div className="relative flex min-h-0 flex-1 overflow-hidden">

        {/* ── 3. Category rail (104px, hidden <560px) ───────────────────── */}
        <nav
          aria-label={t('pos.categories')}
          className="hidden w-[104px] shrink-0 flex-col overflow-y-auto border-r border-line bg-surface pt-2 sm:flex"
        >
          {/* "All" cell */}
          <CategoryCell
            label={t('pos.category.all', 'All')}
            icon={<AllCategoriesIcon />}
            active={resolvedCategoryId === '__all__' || (activeCategoryId === null && orderedCategories.length === 0)}
            onClick={() => {
              setActiveCategoryId(null)
              setSearchQuery('')
            }}
          />
          {orderedCategories.map((cat) => (
            <CategoryCell
              key={cat.id}
              label={cat.name}
              icon={<CategoryIcon name={cat.name} />}
              active={cat.id === resolvedCategoryId && activeCategoryId !== null}
              onClick={() => {
                setActiveCategoryId(cat.id)
                setSearchQuery('')
              }}
            />
          ))}
        </nav>

        {/* ── Phone category chips (shown <560px as horizontal row) ─────── */}
        <div
          aria-label={t('pos.categories')}
          className="absolute top-0 left-0 right-0 z-10 flex h-14 items-center gap-2 overflow-x-auto px-4 pb-0 sm:hidden [-ms-overflow-style:none] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
          style={{ background: 'var(--color-paper)' }}
        >
          <button
            type="button"
            aria-pressed={activeCategoryId === null && !searchQuery}
            onClick={() => { setActiveCategoryId(null); setSearchQuery('') }}
            className={cn(
              'h-10 shrink-0 rounded-full px-4 text-[13px] font-semibold transition-all focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
              activeCategoryId === null && !searchQuery
                ? 'bg-emerald text-on-emerald'
                : 'border border-line bg-surface text-ink-2 hover:border-emerald-line hover:bg-emerald-tint',
            )}
          >
            {t('pos.category.all', 'All')}
          </button>
          {orderedCategories.map((cat) => (
            <button
              key={cat.id}
              type="button"
              aria-pressed={cat.id === resolvedCategoryId && activeCategoryId !== null}
              onClick={() => { setActiveCategoryId(cat.id); setSearchQuery('') }}
              className={cn(
                'h-10 shrink-0 rounded-full px-4 text-[13px] font-semibold transition-all focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                cat.id === resolvedCategoryId && activeCategoryId !== null
                  ? 'bg-emerald text-on-emerald'
                  : 'border border-line bg-surface text-ink-2 hover:border-emerald-line hover:bg-emerald-tint',
              )}
            >
              {cat.name}
            </button>
          ))}
        </div>

        {/* ── 4. Menu grid ─────────────────────────────────────────────── */}
        <div className="min-h-0 flex-1 overflow-y-auto px-5 pb-28 pt-3 sm:pt-5 sm:pb-28">
          {/* Phone: spacer for the chip row */}
          <div className="h-14 sm:hidden" aria-hidden="true" />

          {menuQuery.isLoading ? (
            <MenuSkeleton />
          ) : items.length === 0 ? (
            <EmptyMenu />
          ) : (
            <div>
              {/* Section label */}
              {!searchQuery && orderedCategories.length > 0 ? (
                <div className="mb-3 text-[11px] font-bold uppercase tracking-[.08em] text-ink-3">
                  {orderedCategories.find((c) => c.id === resolvedCategoryId)?.name ?? t('pos.category.all', 'All')}
                </div>
              ) : null}

              {/* Responsive grid: ≥1180→4 cols, 768-1179→3, 560-767→2, <560→2 (156px floor) */}
              <div
                className="grid gap-3"
                style={{
                  gridTemplateColumns: 'repeat(auto-fill, minmax(156px, 1fr))',
                }}
              >
                {visibleItems.map((item, idx) => (
                  <MenuTile
                    key={item.id}
                    item={item}
                    qty={tileQty(item.id)}
                    locale={locale}
                    index={idx}
                    onAdd={() => handleItemTap(item)}
                  />
                ))}
              </div>
            </div>
          )}
        </div>
      </div>

      {/* ── 5. Bottom summary bar (96px, rounded-t-[28px]) ──────────────────── */}
      {/* Only shown when there is an active bill OR items in the immediate cart */}
      {(activeBill || lineCount > 0) ? (
        <SummaryBar
          activeBill={activeBill ?? null}
          lineCount={lineCount}
          grandTotalMinor={grandTotalMinor}
          currency={currency}
          locale={locale}
          discountInput={discountInput}
          discountInvalid={discountInvalid}
          onDiscountChange={setDiscountInput}
          showDiscountInput={canManualDiscount}
          couponCode={couponCode}
          couponStatus={breakdown?.couponStatus ?? null}
          onCouponApply={setCouponCode}
          onCouponClear={() => setCouponCode(null)}
          appliedPromotions={breakdown?.appliedPromotions ?? []}
          session={session}
          attachedMember={attachedMember}
          onMemberAttach={setAttachedMember}
          onMemberClear={() => {
            setAttachedMember(null)
            setLoyaltyRedeemPoints(0)
          }}
          loyaltyRedeemPoints={loyaltyRedeemPoints}
          maxRedeemablePoints={maxRedeemablePoints}
          onLoyaltyRedeemChange={setLoyaltyRedeemPoints}
          onExpand={() => setBillSheetOpen(true)}
          onSend={() => {
            // In bill mode "Send" is handled by BillDetail; here we just open it
            setBillSheetOpen(true)
          }}
          onPay={() => {
            if (openBillId) {
              setBillSheetOpen(true)
            } else {
              setModal('payment')
            }
          }}
        />
      ) : null}

      {/* ── Modals / overlays ─────────────────────────────────────────────── */}

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
          couponCode={couponCode}
          loyaltyMember={attachedMember}
          loyaltyRedeemPoints={loyaltyRedeemPoints}
          currency={currency}
          locale={locale}
          onSuccess={handlePaymentSuccess}
          onClose={() => setModal(null)}
          parkedOrderId={resumedOrder?.orderId ?? null}
          orderType={orderType}
          tableId={null}
        />
      ) : null}

      {showGiftCardSell ? (
        <GiftCardSellModal
          vertical="restaurant"
          session={session}
          currency={currency}
          locale={locale}
          onClose={() => setShowGiftCardSell(false)}
        />
      ) : null}

      {modal === 'receipt' && placedOrder && placedPayment ? (
        <ReceiptView
          order={placedOrder}
          payment={placedPayment}
          locale={locale}
          businessName={session.name}
          tableLabel={null}
          appliedPromotions={lastAppliedPromotions}
          onNew={handleNewOrder}
        />
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
            setBillSheetOpen(false)
          }}
          onClose={() => setShowTableFloor(false)}
        />
      ) : null}

      {/* Bill detail — bottom sheet overlay when billSheetOpen; full-screen on phone */}
      {openBillId ? (
        <BillDetail
          session={session}
          locale={locale}
          billId={openBillId}
          tableLabel={activeTableLabel}
          sheetOpen={billSheetOpen}
          onSheetOpenChange={setBillSheetOpen}
          onBack={() => {
            setOpenBillId(null)
            setBillSheetOpen(false)
          }}
          onPaid={() => {
            setOpenBillId(null)
            setBillSheetOpen(false)
          }}
        />
      ) : null}

      {/* New bill dialog */}
      {showOpenBillDialog ? (
        <OpenBillDialog
          session={session}
          tables={tables}
          onCreated={handleBillCreated}
          onClose={() => setShowOpenBillDialog(false)}
        />
      ) : null}

      {/* Phone bill selector overlay */}
      {showBillSelector ? (
        <BillSelectorOverlay
          bills={openBillsList}
          activeBillId={openBillId}
          locale={locale}
          onSelect={(billId) => {
            setOpenBillId(billId)
            setShowBillSelector(false)
            setBillSheetOpen(false)
          }}
          onNewBill={() => {
            setShowBillSelector(false)
            setShowOpenBillDialog(true)
          }}
          onClose={() => setShowBillSelector(false)}
        />
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// BillTabsBar — 88px bar, desktop (sm+)
// ---------------------------------------------------------------------------

function BillTabsBar({
  bills,
  activeBillId,
  locale,
  onTabClick,
  onNewBill,
  onSelectorClick,
}: {
  bills: BillSummaryResponse[]
  activeBillId: string | null
  locale: string
  onTabClick: (billId: string) => void
  onNewBill: () => void
  onSelectorClick: () => void
}) {
  const { t } = useTranslation()
  const activeBill = activeBillId ? bills.find((b) => b.id === activeBillId) : null

  return (
    <div className="h-[88px] shrink-0 border-b border-line bg-surface">
      {/* Desktop tabs (sm+) */}
      <div
        role="tablist"
        aria-label={t('bills.trayTitle')}
        className="hidden h-full items-stretch gap-1 overflow-x-auto px-6 sm:flex [-ms-overflow-style:none] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
      >
        {bills.map((bill) => {
          const isActive = bill.id === activeBillId

          return (
            <button
              key={bill.id}
              type="button"
              role="tab"
              aria-selected={isActive}
              onClick={() => onTabClick(bill.id)}
              aria-label={t('bills.activeBillAriaLabel', { label: bill.guestLabel })}
              className={cn(
                'flex shrink-0 flex-col justify-center px-[18px] transition-colors focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald',
                isActive
                  ? 'border-b-[3px] border-emerald'
                  : 'border-b-[3px] border-transparent hover:bg-emerald-tint/40',
              )}
            >
              <div className="flex items-center gap-[7px]">
                <span
                  className={cn(
                    'text-[15px] font-bold',
                    isActive ? 'text-ink' : 'text-ink-2',
                  )}
                >
                  {bill.guestLabel}
                </span>
                {bill.lineCount > 0 ? (
                  <span
                    className={cn(
                      'text-[13px] font-semibold',
                      isActive ? 'text-ink-3' : 'text-ink-3',
                    )}
                  >
                    · {bill.lineCount}
                  </span>
                ) : null}
              </div>
              <div
                className={cn(
                  'tnum mt-[3px] font-mono text-[12px] font-semibold',
                  isActive ? 'text-emerald-2' : 'text-ink-3',
                )}
              >
                {formatMoney(bill.runningTotalMinor, bill.currency, locale)}
              </div>
            </button>
          )
        })}

        <div className="flex-1" />

        {/* New bill button (dashed) */}
        <div className="flex items-center">
          <button
            type="button"
            onClick={onNewBill}
            aria-label={t('bills.newBillAriaLabel')}
            className="grid size-[52px] place-items-center rounded-xl border-[1.5px] border-dashed border-emerald-line bg-surface text-emerald-2 transition-all hover:bg-emerald-tint focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <Plus className="size-5" aria-hidden="true" />
          </button>
        </div>
      </div>

      {/* Phone bill selector button (<sm) */}
      <div className="flex h-full items-center gap-2 px-4 sm:hidden">
        <button
          type="button"
          onClick={onSelectorClick}
          aria-label={t('bills.billSelectorAriaLabel', {
            label: activeBill?.guestLabel ?? t('bills.noBills'),
            total: activeBill ? formatMoney(activeBill.runningTotalMinor, activeBill.currency, locale) : '',
          })}
          className="flex min-w-0 flex-1 h-11 items-center gap-2 rounded-xl bg-emerald-tint border-[1.5px] border-emerald px-3 text-left focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          {activeBill ? (
            <>
              <span className="min-w-0 flex-1 truncate text-[14px] font-bold text-emerald-2">
                {activeBill.guestLabel}
              </span>
              <span className="tnum shrink-0 font-mono text-[13px] font-semibold text-emerald-2">
                {formatMoney(activeBill.runningTotalMinor, activeBill.currency, locale)}
              </span>
            </>
          ) : (
            <span className="flex-1 text-[14px] font-semibold text-emerald-2">
              {t('bills.noBillsHint')}
            </span>
          )}
          <ChevronDown className="size-4 shrink-0 text-emerald-2" aria-hidden="true" />
        </button>
        <button
          type="button"
          onClick={onNewBill}
          aria-label={t('bills.newBillAriaLabel')}
          className="grid size-11 shrink-0 place-items-center rounded-xl border-[1.5px] border-dashed border-emerald-line bg-surface text-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          <Plus className="size-[18px]" aria-hidden="true" />
        </button>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// CategoryCell — left rail cell (76px tall)
// ---------------------------------------------------------------------------

function CategoryCell({
  label,
  icon,
  active,
  onClick,
}: {
  label: string
  icon: React.ReactNode
  active: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        'flex h-[76px] w-full flex-col items-center justify-center gap-1.5 transition-colors focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald',
        active ? 'bg-emerald-tint' : 'hover:bg-ink-50',
      )}
    >
      <span className={active ? 'text-emerald-2' : 'text-ink-3'}>{icon}</span>
      <span
        className={cn(
          'text-[11px] leading-tight',
          active ? 'font-bold text-emerald-2' : 'font-semibold text-ink-3',
        )}
      >
        {label}
      </span>
    </button>
  )
}

// ---------------------------------------------------------------------------
// CategoryIcon — generic icon by category name
// ---------------------------------------------------------------------------

function CategoryIcon({ name }: { name: string }) {
  const lower = name.toLowerCase()
  if (lower.includes('drink') || lower.includes('minum') || lower.includes('beverage')) {
    return (
      <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M5 3h14l-1.5 5H6.5Z" /><path d="M7 8v10a3 3 0 0 0 3 3h4a3 3 0 0 0 3-3V8" />
      </svg>
    )
  }
  if (lower.includes('dessert') || lower.includes('sweet') || lower.includes('pencuci')) {
    return (
      <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M4 19h16" /><path d="M6 19a6 6 0 0 1 12 0" /><path d="M12 9V5" />
      </svg>
    )
  }
  if (lower.includes('grill') || lower.includes('bakar')) {
    return (
      <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M4 20h16" /><path d="M6 16h12" /><path d="M8 16V6" /><path d="M12 16V4" /><path d="M16 16V7" />
      </svg>
    )
  }
  if (lower.includes('side') || lower.includes('pelengkap')) {
    return (
      <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <circle cx="12" cy="12" r="9" /><circle cx="12" cy="12" r="4" />
      </svg>
    )
  }
  // Default: generic food/restaurant icon
  return (
    <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M3 11h18" /><path d="M5 11a7 7 0 0 1 14 0" /><path d="M4 15h16" /><path d="M6 19h12" />
    </svg>
  )
}

function AllCategoriesIcon() {
  return (
    <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="3" width="7" height="7" rx="1" /><rect x="14" y="3" width="7" height="7" rx="1" />
      <rect x="3" y="14" width="7" height="7" rx="1" /><rect x="14" y="14" width="7" height="7" rx="1" />
    </svg>
  )
}

// ---------------------------------------------------------------------------
// MenuTile — 3b design tile
// ---------------------------------------------------------------------------

function MenuTile({
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
  const isLowStock = item.stockQuantity != null && item.stockQuantity > 0 && item.stockQuantity <= 5
  const delayMs = Math.min(index, 12) * 40
  const hasImage = !!item.imageUrl

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
        'reveal relative flex flex-col overflow-hidden rounded-xl border bg-surface text-left transition-all duration-200',
        unavailable
          ? 'cursor-not-allowed border-line opacity-55'
          : qty > 0
            ? 'border-emerald shadow-md ring-2 ring-emerald/20 hover:-translate-y-0.5 hover:shadow-lg active:scale-[0.98]'
            : 'border-line shadow-sm hover:-translate-y-0.5 hover:border-emerald-line hover:shadow-md active:scale-[0.98]',
      )}
    >
      {/* Image area (104px) or placeholder */}
      {hasImage ? (
        <div className="relative h-[104px] w-full overflow-hidden bg-ink-50">
          <img
            src={item.imageUrl!}
            alt={item.name}
            loading="lazy"
            className={cn(
              'h-full w-full object-cover transition-transform duration-300',
              !unavailable && 'group-hover:scale-[1.04]',
              unavailable && 'grayscale',
            )}
          />
          {/* Low stock / sold-out badge */}
          {isLowStock && !unavailable ? (
            <span className="absolute right-2 top-2 z-10 flex h-6 items-center rounded-full bg-tint-warning px-2.5 text-[11px] font-bold text-amber-2">
              {t('menu.stock.lowStock', { count: item.stockQuantity })}
            </span>
          ) : unavailable ? (
            <span className="absolute right-2 top-2 z-10 flex h-6 items-center rounded-full bg-ink-50 px-2.5 text-[11px] font-semibold text-ink-3">
              {t('pos.soldOut')}
            </span>
          ) : null}
          {/* Qty badge */}
          {!unavailable && qty > 0 ? (
            <span className="tnum absolute right-2 top-2 grid h-6 min-w-6 place-items-center rounded-full bg-emerald px-1.5 font-mono text-xs font-bold text-on-emerald shadow-sm">
              {qty}
            </span>
          ) : null}
        </div>
      ) : (
        /* Compact text tile for image-less items (72px) */
        <div className="relative flex h-[72px] w-full items-center justify-center bg-ink-50">
          <ImageOff className="size-5 text-ink-200" aria-hidden="true" />
          {isLowStock && !unavailable ? (
            <span className="absolute right-2 top-2 flex h-6 items-center rounded-full bg-tint-warning px-2.5 text-[11px] font-bold text-amber-2">
              {t('menu.stock.lowStock', { count: item.stockQuantity })}
            </span>
          ) : unavailable ? (
            <span className="absolute right-2 top-2 flex h-6 items-center rounded-full bg-ink-50 px-2.5 text-[11px] font-semibold text-ink-3">
              {t('pos.soldOut')}
            </span>
          ) : null}
          {!unavailable && qty > 0 ? (
            <span className="tnum absolute right-2 top-2 grid h-6 min-w-6 place-items-center rounded-full bg-emerald px-1.5 font-mono text-xs font-bold text-on-emerald shadow-sm">
              {qty}
            </span>
          ) : null}
        </div>
      )}

      {/* Content */}
      <div className="flex flex-col px-3 pb-3 pt-2.5">
        <span
          className={cn(
            'line-clamp-2 text-[13px] font-semibold leading-snug',
            unavailable ? 'text-ink-3' : 'text-ink',
          )}
        >
          {item.name}
        </span>
        <div className="mt-1.5 flex items-end justify-between">
          <span
            className={cn(
              'tnum font-mono text-[14px] font-semibold',
              unavailable ? 'text-ink-3/50' : 'text-ink',
            )}
          >
            {formatMoney(item.priceMinor, item.currency, locale)}
          </span>
          {item.modifierGroups.length > 0 && !unavailable ? (
            <span className="rounded-full bg-emerald-tint px-1.5 py-0.5 text-[10px] font-semibold text-emerald-2">
              {t('pos.hasOptions')}
            </span>
          ) : null}
        </div>
      </div>
    </button>
  )
}

// ---------------------------------------------------------------------------
// SummaryBar — bottom bar (~96px)
// ---------------------------------------------------------------------------

function SummaryBar({
  activeBill,
  lineCount,
  grandTotalMinor,
  currency,
  locale,
  discountInput,
  discountInvalid,
  onDiscountChange,
  showDiscountInput,
  couponCode,
  couponStatus,
  onCouponApply,
  onCouponClear,
  appliedPromotions,
  session,
  attachedMember,
  onMemberAttach,
  onMemberClear,
  loyaltyRedeemPoints,
  maxRedeemablePoints,
  onLoyaltyRedeemChange,
  onExpand,
  onSend,
  onPay,
}: {
  activeBill: BillSummaryResponse | null
  lineCount: number
  grandTotalMinor: number
  currency: string
  locale: string
  discountInput: string
  discountInvalid: boolean
  onDiscountChange: (v: string) => void
  /** Manual discount is owner/manager-only (ADR 0026 §5) — hidden for a cashier session. */
  showDiscountInput: boolean
  couponCode: string | null
  couponStatus: 'APPLIED' | 'INVALID' | 'EXHAUSTED' | null
  onCouponApply: (code: string) => void
  onCouponClear: () => void
  appliedPromotions: AppliedPromotionResponse[]
  session: CompanySession
  /** Phase 4 (ADR 0027): the attached loyalty member — walk-in cart mode only, mirrors the coupon
   * scope decision (bills are a follow-up). */
  attachedMember: MemberResponse | null
  onMemberAttach: (member: MemberResponse) => void
  onMemberClear: () => void
  loyaltyRedeemPoints: number
  maxRedeemablePoints: number
  onLoyaltyRedeemChange: (points: number) => void
  onExpand: () => void
  onSend: () => void
  onPay: () => void
}) {
  const { t } = useTranslation()
  const displayTotal = activeBill
    ? formatMoney(activeBill.runningTotalMinor, activeBill.currency, locale)
    : formatMoney(grandTotalMinor, currency, locale)
  const displayName = activeBill?.guestLabel ?? ''
  const displayCount = activeBill?.lineCount ?? lineCount

  // Count "unsent" lines — we don't have the data at summary level; show total items
  const unsentCount = displayCount // simplified: show all items as "send" target when a bill is active

  return (
    <div
      className="fixed inset-x-0 bottom-0 z-30 flex flex-col rounded-t-[28px] bg-surface shadow-[0_-12px_32px_rgba(15,23,42,.10)]"
      style={{ boxSizing: 'border-box' }}
    >
      {/* Drag handle */}
      <span className="absolute left-1/2 top-2.5 h-1 w-10 -translate-x-1/2 rounded-full bg-line" aria-hidden="true" />

      {/* Coupon + manual discount — walk-in cart mode only (not bill mode). Coupons on bills are a
          follow-up (ADR 0026 §"Consequences"); the manual discount input is owner/manager-only. */}
      {!activeBill && lineCount > 0 ? (
        <div className="flex flex-col gap-2.5 px-5 pt-5 pb-1">
          <CouponField
            code={couponCode}
            status={couponStatus}
            onApply={onCouponApply}
            onClear={onCouponClear}
            className="max-w-sm"
          />
          <AppliedPromotionChips promotions={appliedPromotions} currency={currency} locale={locale} />
          <MemberField
            session={session}
            currency={currency}
            locale={locale}
            member={attachedMember}
            onAttach={onMemberAttach}
            onClear={onMemberClear}
            redeemPoints={loyaltyRedeemPoints}
            maxRedeemable={maxRedeemablePoints}
            onRedeemChange={onLoyaltyRedeemChange}
            className="max-w-sm"
          />
          {showDiscountInput ? (
            <div className="flex items-center gap-2">
              <label
                htmlFor="pos-discount"
                className="shrink-0 text-sm font-medium text-ink-2"
              >
                {t('pos.addDiscount')}
              </label>
              <input
                id="pos-discount"
                type="number"
                min="0"
                step="any"
                value={discountInput}
                onChange={(e) => onDiscountChange(e.target.value)}
                placeholder="0"
                aria-describedby={discountInvalid ? 'pos-discount-error' : undefined}
                className={cn(
                  'h-11 w-40 rounded-xl border bg-surface px-3 text-sm text-ink placeholder:text-ink-3/50 transition-colors',
                  'focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15',
                  discountInvalid ? 'border-loss' : 'border-line',
                )}
              />
              {discountInvalid ? (
                <p id="pos-discount-error" className="text-xs text-loss" role="alert">
                  {t('pos.discountInvalid')}
                </p>
              ) : null}
            </div>
          ) : null}
        </div>
      ) : null}

      {/* Main action row */}
      <div className="flex h-24 items-center gap-3.5 px-5">
        {/* Chevron expand */}
        <button
          type="button"
          onClick={onExpand}
          aria-label={t('bills.viewBill')}
          className="grid size-11 shrink-0 place-items-center rounded-full bg-ink-50 text-ink-2 transition-colors hover:bg-ink-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          <ChevronUp className="size-[18px]" aria-hidden="true" />
        </button>

        {/* Item / bill info */}
        <div className="min-w-0 flex-1">
          <div className="text-[15px] font-bold text-ink">
            {displayCount > 0
              ? t('bills.itemsAndBill', { n: displayCount, bill: displayName })
              : displayName}
          </div>
          <div className="mt-0.5 text-[11px] text-ink-3">
            {displayCount > 0
              ? t('bills.unsent', { n: unsentCount })
              : t('bills.noBillsHint')}
          </div>
        </div>

        {/* Send button (secondary) */}
        {displayCount > 0 ? (
          <button
            type="button"
            onClick={onSend}
            className="flex h-14 items-center gap-2 rounded-xl border border-emerald-line bg-emerald-tint px-5 text-[15px] font-bold text-emerald-2 transition-all hover:bg-emerald-tint/80 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <Send className="size-[17px]" aria-hidden="true" />
            {t('bills.sendN', { n: unsentCount })}
          </button>
        ) : null}

        {/* Pay button (primary) */}
        <button
          type="button"
          onClick={onPay}
          disabled={displayCount === 0 && !activeBill}
          className="tnum h-14 rounded-xl bg-emerald px-6 font-mono text-[15px] font-bold text-on-emerald transition-all hover:bg-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald disabled:opacity-40"
        >
          {t('bills.payTotal', { total: displayTotal })}
        </button>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// MenuSkeleton
// ---------------------------------------------------------------------------

function MenuSkeleton() {
  return (
    <div
      className="grid gap-3"
      style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(156px, 1fr))' }}
    >
      {Array.from({ length: 8 }).map((_, i) => (
        <div key={i} className="overflow-hidden rounded-xl border border-line bg-surface" aria-hidden="true">
          <div className="shimmer h-[104px] w-full" />
          <div className="space-y-2 p-3">
            <div className="shimmer h-3.5 w-3/4 rounded-md" />
            <div className="shimmer h-3 w-1/3 rounded-md" />
          </div>
        </div>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// EmptyMenu
// ---------------------------------------------------------------------------

function EmptyMenu() {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col items-center justify-center px-6 py-20 text-center">
      <div className="mb-5 grid size-16 place-items-center rounded-2xl bg-emerald-tint text-emerald-2">
        <Utensils className="size-7" aria-hidden="true" />
      </div>
      <h2 className="font-display text-xl font-bold text-ink">{t('pos.emptyMenu')}</h2>
      <p className="mx-auto mt-2 max-w-xs text-sm text-ink-3">{t('pos.emptyMenuHint')}</p>
    </div>
  )
}

// ---------------------------------------------------------------------------
// BillSelectorOverlay — phone full-screen bill list
// ---------------------------------------------------------------------------

function BillSelectorOverlay({
  bills,
  activeBillId,
  locale,
  onSelect,
  onNewBill,
  onClose,
}: {
  bills: BillSummaryResponse[]
  activeBillId: string | null
  locale: string
  onSelect: (billId: string) => void
  onNewBill: () => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  return (
    <div
      className="fixed inset-0 z-50 flex flex-col bg-paper"
      role="dialog"
      aria-modal="true"
      aria-label={t('bills.trayTitle')}
    >
      <div className="flex items-center justify-between border-b border-line bg-surface px-4 py-3">
        <h2 className="font-display text-lg font-semibold text-ink">{t('bills.trayTitle')}</h2>
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
        {bills.length === 0 ? (
          <p className="py-10 text-center text-sm text-ink-3">{t('bills.noBills')}</p>
        ) : (
          <div className="space-y-2">
            {bills.map((bill) => (
              <button
                key={bill.id}
                type="button"
                onClick={() => onSelect(bill.id)}
                className={cn(
                  'w-full rounded-xl border px-4 py-3 text-left transition-all focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                  bill.id === activeBillId
                    ? 'border-emerald bg-emerald-tint'
                    : 'border-line bg-surface hover:border-emerald-line hover:bg-emerald-tint/30',
                )}
              >
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-ink">{bill.guestLabel}</span>
                  <span className="tnum font-mono text-sm font-semibold text-ink">
                    {formatMoney(bill.runningTotalMinor, bill.currency, locale)}
                  </span>
                </div>
                <div className="mt-1 text-xs text-ink-3">
                  {t('bills.lineCount', { n: bill.lineCount })}
                </div>
              </button>
            ))}
          </div>
        )}
      </div>
      <div className="border-t border-line p-4">
        <button
          type="button"
          onClick={onNewBill}
          className="flex w-full items-center justify-center gap-2 rounded-xl border-[1.5px] border-dashed border-emerald-line bg-surface py-3 text-[15px] font-semibold text-emerald-2 hover:bg-emerald-tint focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          <Plus className="size-5" aria-hidden="true" />
          {t('bills.newBill')}
        </button>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// OpenBillDialog — create a new bill (was inner of BillsTray)
// ---------------------------------------------------------------------------

function OpenBillDialog({
  session,
  tables,
  onCreated,
  onClose,
}: {
  session: CompanySession
  tables: import('./api').TableResponse[]
  onCreated: (billId: string) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
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
      <div className="w-full max-w-sm rounded-[20px] border border-line bg-surface shadow-xl">
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

// ---------------------------------------------------------------------------
// NoCompany
// ---------------------------------------------------------------------------

function NoCompany() {
  const { t } = useTranslation()
  return (
    <div className="grid min-h-screen place-items-center bg-paper px-5">
      <div className="w-full max-w-md rounded-[20px] border border-line bg-surface p-10 text-center shadow-sm">
        <div className="mx-auto mb-4 grid size-14 place-items-center rounded-2xl bg-emerald-tint text-emerald-2">
          <Utensils className="size-6" aria-hidden="true" />
        </div>
        <h2 className="font-display text-xl font-bold text-ink">{t('dashboard.noCompany')}</h2>
        <p className="mt-2 text-sm text-ink-3">{t('pos.noCompanyHint')}</p>
        <Link
          to="/onboarding"
          className="mt-6 inline-block rounded-xl bg-emerald px-5 py-2.5 text-sm font-semibold text-on-emerald shadow-sm transition-colors hover:bg-emerald-2"
        >
          {t('nav.onboarding')}
        </Link>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------

function parseDiscountInput(input: string, currency: string): number {
  if (!input || input.trim() === '') return 0
  const major = Number(input)
  if (isNaN(major) || major < 0) return 0
  const exp = isoMinorExponent(currency)
  return Math.round(major * 10 ** exp)
}

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

function lineKey(menuItemId: string, selectedOptionIds: string[]): string {
  return `${menuItemId}::${[...selectedOptionIds].sort().join(',')}`
}
