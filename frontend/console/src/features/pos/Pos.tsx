/**
 * Pos.tsx — 3b "Bill tabs" redesign.
 *
 * Layout (tablet 768–1179px portrait baseline):
 *   1. Bill tabs bar (~88px, white, border-b)  ← replaces BillsTray slide-in panel
 *   2. Search row (~64px)
 *   3. Left category rail (104px, white, border-r) + Menu grid (flex-1)
 *   4. Bottom summary bar (~96px, white, rounded-t-sheet, strong up-shadow)
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
import { useState, useMemo, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import {
  ArrowLeft,
  Banknote,
  BookOpen,
  CalendarClock,
  ChefHat,
  ClipboardCheck,
  History,
  ClipboardList,
  FileText,
  Gift,
  KeyRound,
  LogIn,
  LogOut,
  Monitor,
  Moon,
  Package,
  Percent,
  Sun,
  Table2,
  Undo2,
  UserRound,
} from 'lucide-react'
import { useSession, type CompanySession } from '@/lib/session'
import { useAuth, hasAnyRole, effectiveRoles } from '@/lib/authContext'
import { isNativeShell } from '@/lib/escpos/transport'
import { accountMenuVisibility } from '@/features/pos-shell/layout/accountMenuGate'
import { useTheme } from '@/lib/theme'
import { localeOf } from '@/i18n'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import { OutletPicker } from '@/components/OutletPicker'
import { OutletGate } from '@/components/OutletGate'
import { GiftCardSellModal } from '@/components/GiftCardSellModal'
import { CouponField } from '@/components/CouponField'
import { MemberField } from '@/components/MemberField'
import { OfflineHint } from './offline/OfflineHint'
import type { MemberResponse } from '@/features/loyalty/api'
import { useOffline } from './offline/useOffline'
import { useCachedCatalogFallback } from './offline/catalogCache'
import { computeProvisionalPricing, toDisplayBreakdown } from './offline/provisionalPricing'
import type { EffectiveRulesResponse } from './offline/provisionalPricing'
import { SyncCenter } from './offline/SyncCenter'
import type { SaleQueueRow } from './offline/db'
import { useDisplayPublisher } from './display/displayPublisher'
import { deriveCategories, visibleMenuItems } from './lib/categories'
import { displayCategoryName } from './lib/categoryCanon'
import { lineKey } from './lib/lineKey'
import { parseDiscountInput } from './lib/discountInput'
import {
  billDisplayBreakdown,
  cartDisplayBreakdown,
  cartDisplayLines,
} from './lib/displayPayload'
import {
  useMenu,
  useCategories,
  useEffectiveRules,
  useItemPopularity,
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
import { useBills, useAppendLines,
} from './billsApi'
import type { AppliedPromotionResponse, OrderResponse, PaymentResponse, PriceBreakdownResponse } from './api'
import { useQuote, useGetOrder } from './api'
import { BillTabsBar } from './components/BillTabsBar'
import { CategoryCell, CategoryIcon, AllCategoriesIcon } from './components/CategoryRail'
import { MenuTile } from './components/MenuTile'
import { SummaryBar } from './components/SummaryBar'
import { WalkInCartSheet } from './components/WalkInCartSheet'
import { ReturnSaleDialog } from './components/ReturnSaleDialog'
import { canReturnPayment } from './lib/returnSale'
import { MenuSkeleton, EmptyMenu, EmptyCategory } from './components/MenuStates'
import { BillSelectorOverlay } from './components/BillSelectorOverlay'
import { OpenBillDialog } from './components/OpenBillDialog'
import { NoCompany } from './components/NoCompany'
import { RegisterSheet } from './RegisterSheet'
import { DailySummary } from './DailySummary'
import { useCurrentRegisterSession } from './registerApi'
import { noConfirmedOpenSession, registerMenuLabelKey } from './lib/registerGate'
import { useOperatorSession } from '@/features/operator/operatorSessionContext'
import { operatorSignInRequired } from '@/features/operator/operatorGate'
import { OperatorPinSheet } from '@/features/operator/OperatorPinSheet'
import { useOutletPinPolicy } from '@/features/operator/api'
import { PosStatusBar } from '@/features/pos-shell/layout/PosStatusBar'
import { PosPhoneHeader } from '@/features/pos-shell/layout/PosPhoneHeader'
import { BillDock, type DockAction, type DockLine } from '@/features/pos-shell/layout/BillDock'
import { TillMenuSheet, type TillMenuItem } from '@/features/pos-shell/layout/TillMenuSheet'
import {
  usePrinterStatusAction,
  toTillMenuItem,
} from '@/features/pos-shell/layout/usePrinterStatusAction'
import { useIsPhone } from '@/components/mobile/useIsPhone'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { backIntentFor } from '@/components/mobile/backGuardProtocol'
import { dockActions, dueLabelKey, peekLines } from './lib/dockLines'
import { StocktakeSheet } from '@/features/stocktake/StocktakeSheet'
import { SalesHistorySheet } from './SalesHistorySheet'
import { ClosingHistorySheet } from './ClosingHistorySheet'

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
  const navigate = useNavigate()
  const locale = localeOf(i18n.language)

  const menuQuery = useMenu(session)
  const categoriesQuery = useCategories(session)
  const tablesQuery = useTables(session)
  const parkedQuery = useParkedOrders(session)
  const billsQuery = useBills(session)
  const appendLines = useAppendLines(session)
  const effectiveRulesQuery = useEffectiveRules(session)
  const popularityQuery = useItemPopularity(session)
  // "Open the register first" (owner request): the outlet's current OPEN session, or null once
  // the server has confirmed the drawer is closed (204). Shared by the entry auto-prompt and the
  // payment gate below (registerGate.ts) — same query RegisterSheet itself reads, so opening it
  // costs no extra round trip.
  const registerSessionQuery = useCurrentRegisterSession(session)
  // The till-menu register entry reflects the drawer's ACTUAL state (owner request "kalo sudah
  // closing harusnya berubah jadi buka kasir") — decision is the pure registerMenuLabelKey core,
  // mirroring RegisterSheet's own state-aware title. Open → "Closing kasir"; confirmed-closed →
  // "Buka kasir"; loading/error → the neutral combined label.
  const registerMenuLabel = t(
    registerMenuLabelKey({
      isLoading: registerSessionQuery.isLoading,
      isError: registerSessionQuery.isError,
      session: registerSessionQuery.data,
    }),
  )

  // ADR 0049 P3b — the Business-app device terminal's operator gate. `isDeviceTerminal` is derived
  // from the VERIFIED token claim (auth.actorType, ADR 0049), never a client-side guess; a normal
  // `user` login (today's exact behavior) leaves this false everywhere below, so the operator
  // chip/PIN gate/till-menu sign-out never render or fire for it.
  const isDeviceTerminal = auth.actorType === 'device'
  const operatorSession = useOperatorSession()
  const [showOperatorPinSheet, setShowOperatorPinSheet] = useState(false)
  // ADR 0049 P3d — the outlet's require-PIN policy, prefetched here (not lazily inside
  // OperatorPinSheet) so it's already in cache by the time the sheet opens — it never flashes the
  // PIN pad before flipping to the no-PIN picker. Enabled only on a device terminal, mirroring the
  // roster's own gating; a missing/erroring read defaults CLOSED (`requirePin: true`, the server's
  // own safe default — never assume no-PIN on a read failure).
  const pinPolicyQuery = useOutletPinPolicy(session, session.businessId, isDeviceTerminal)
  const operatorRequirePin = pinPolicyQuery.data?.requirePin ?? true
  // ADR 0049 P3b slice 2 — which of the till-menu's account actions (elevate / the three logouts)
  // this login sees — see accountMenuGate.ts's doc for the exact rules.
  const accountMenu = accountMenuVisibility({
    isDeviceTerminal,
    operatorSignedIn: operatorSession.operator != null,
    elevatedRoles: auth.elevatedRoles,
  })

  // Phase 5 offline mode (ADR 0028). When the live catalog/rules queries have no data at all (a
  // fresh page load while offline — the common case is a query that already succeeded THIS session
  // simply keeps its last-good `data` on a failed background refetch, so this fallback only kicks
  // in for the "opened the app already offline" case).
  const { offline, queuedCount, rejectedCount } = useOffline()
  // Always-visible printer status (P1 printing-flow hardening) — see the hook's own doc for why
  // this lives here rather than inside PosStatusBar (which stays stateless presentation).
  const printerStatusAction = usePrinterStatusAction()
  const [showSyncCenter, setShowSyncCenter] = useState(false)
  const [showTillMenu, setShowTillMenu] = useState(false)
  const [showRegisterSheet, setShowRegisterSheet] = useState(false)
  // True while the register sheet is showing BECAUSE the payment gate redirected the cashier here
  // (vs. a manual till-menu open) — only then does the sheet show the explanatory reason line.
  const [registerGateActive, setRegisterGateActive] = useState(false)
  const [showStocktakeSheet, setShowStocktakeSheet] = useState(false)
  const [showSalesHistory, setShowSalesHistory] = useState(false)
  // The manager/owner past closed-day history browse (till menu, owner/manager only).
  const [showClosingHistory, setShowClosingHistory] = useState(false)
  // The daily transaction summary (Z-report) overlay. An explicit id (set by the close flow's "Cetak
  // ringkasan") pins the session to summarize; null lets DailySummary resolve the current/last one
  // (the till-menu "Ringkasan hari ini" entry).
  const [showSummary, setShowSummary] = useState(false)
  const [summarySessionId, setSummarySessionId] = useState<string | null>(null)
  // P4: the dock's Send/Pay reach INTO the bill sheet — each ++ asks BillDetail to fire the
  // kitchen ticket / the pay modal as soon as the bill is loaded (no manual sheet detour).
  const [autoKotToken, setAutoKotToken] = useState(0)
  const [autoPayToken, setAutoPayToken] = useState(0)
  const cachedMenu = useCachedCatalogFallback<MenuItem[]>(
    session.companyId,
    'restaurant',
    'menu',
    offline && !menuQuery.data,
  )
  const cachedCategories = useCachedCatalogFallback<CategoryResponse[]>(
    session.companyId,
    'restaurant',
    'menuCategories',
    offline && !categoriesQuery.data,
  )
  const cachedEffectiveRules = useCachedCatalogFallback<EffectiveRulesResponse>(
    session.companyId,
    'restaurant',
    'effectiveRules',
    offline && !effectiveRulesQuery.data,
  )
  const effectiveRules = effectiveRulesQuery.data ?? cachedEffectiveRules ?? null

  // Phase 6 (ADR 0029): the customer-display publisher — a no-op until the cashier opens a display
  // window (see the "Customer display" utility button below and displayPublisher.ts).
  const displayPublisher = useDisplayPublisher(session.businessId)

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
  // Returning a sale (full refund, ADR 0061) is owner/manager-only. Merged roles so an ELEVATED
  // device terminal (ADR 0049 P3b) lights the affordance up; the gateway (SALE_REVERSAL_ROLES) is
  // the real boundary. The refund posts on the personal/elevated bearer (useRefundPayment) — for a
  // device terminal that means the elevation token, so this reads owner/manager only from an ACTUAL
  // elevation (device credentials are cashier-tier by design; a base owner/manager device is not a
  // provisioned shape). If one ever were, the button would fail CLOSED at the gateway, never widen.
  const canReturnSale = hasAnyRole(effectiveRoles(auth.roles, auth.elevatedRoles), 'owner', 'manager')
  // The past closed-day history browse is owner/manager-only. Merged roles for the same elevated
  // device-terminal reason as the return affordance; the read is POS_ROLES at the gateway (like the
  // Z-report), so this hides an entry a cashier does not need, not a security boundary.
  const canViewClosingHistory = hasAnyRole(
    effectiveRoles(auth.roles, auth.elevatedRoles),
    'owner',
    'manager',
  )
  // Open-bill lockdown (owner rule): cancelling a bill WITH lines and removing/decrementing lines
  // are owner/manager-only — once a bill holds items its flow must end in payment. Merged roles so
  // an elevated device terminal lights the affordances up; the server guard
  // (BillWriter.requireOwnerOrManager, 403 bill-mutation-forbidden) is the real boundary. An EMPTY
  // bill stays cancellable by anyone (wrong table opened) — see lib/billPermissions.ts.
  const canVoidBill = hasAnyRole(effectiveRoles(auth.roles, auth.elevatedRoles), 'owner', 'manager')

  // Modal / overlay state
  const [modal, setModal] = useState<'payment' | 'receipt' | 'cart' | null>(null)
  // The manager-gated "Return sale" confirm/refund dialog over the post-sale receipt (ADR 0061).
  const [showReturnDialog, setShowReturnDialog] = useState(false)
  const [modifierItem, setModifierItem] = useState<MenuItem | null>(null)
  const [placedOrder, setPlacedOrder] = useState<OrderResponse | null>(null)
  const [placedPayment, setPlacedPayment] = useState<PaymentResponse | null>(null)
  // Phase 5 (ADR 0028): true when the last placed order was enqueued offline (a client-side,
  // not-yet-confirmed receipt) rather than a real server response.
  const [placedProvisional, setPlacedProvisional] = useState(false)
  // The applied-promotion detail from the LAST live quote before payment — the checkout/pay-parked
  // response itself carries only the aggregate discount (ADR 0026), so the receipt uses this snapshot.
  const [lastAppliedPromotions, setLastAppliedPromotions] = useState<AppliedPromotionResponse[]>([])
  const [showParkedTray, setShowParkedTray] = useState(false)
  const [showTableFloor, setShowTableFloor] = useState(false)

  // Open bill mode (BillDetail sheet)
  const [openBillId, setOpenBillId] = useState<string | null>(null)
  // Bottom sheet open state (expands the BillDetail sheet from summary bar)
  const [billSheetOpen, setBillSheetOpen] = useState(false)

  // Native Till Android v2 — on a phone the bill is a deck that is always attached, not a sheet you
  // open. This is the deck's ONE piece of state: peeking, or dragged up to the full ticket. The
  // bill-mode deck has its own copy of it inside BillDetail (it owns the bill's lines and
  // mutations), so the two never render at once — Pos only mounts the deck for the walk-in cart.
  const isPhone = useIsPhone()
  const [dockExpanded, setDockExpanded] = useState(false)
  // BACK collapses the expanded deck before it leaves the till (ADR 0075: back pops, never pushes).
  useBackDismiss(() => setDockExpanded(false), isPhone && dockExpanded && !openBillId)

  // How many of each menu item the OPEN BILL holds unpaid — reported up by BillDetail, which is
  // the only place the full bill lives (the bill LIST response carries a line count and nothing
  // per item). Lets the catalog tiles badge a count in bill mode, as they do for the cart.
  // Stamped with the bill it describes: switching bills would otherwise badge the new bill's tiles
  // with the previous one's counts for the frame or two before its query resolves.
  const [billQty, setBillQty] = useState<{ billId: string | null; byItem: Record<string, number> }>({
    billId: null,
    byItem: {},
  })

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

  // Data — offline: falls back to the last cached catalog read (see hooks above) when the live
  // query never resolved this session.
  const items = menuQuery.data ?? cachedMenu ?? []
  const categories = (categoriesQuery.data ?? cachedCategories ?? []).filter((c) => c.active)
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
  // null = the "All" tab (the DEFAULT): a cashier opening the POS sees the whole menu, never a
  // single — possibly empty — first category. '' matches no category id, so the item filter's
  // `!cat → return true` branch shows everything.
  const resolvedCategoryId: string = activeCategoryId ?? ''

  // Filtered items — memoized; hoist trimmed lower-case search once (logic in lib/categories.ts).
  // The "All" tab (and only it) orders best-sellers first: units ever sold per item (completed
  // walk-in orders + paid bill lines, useItemPopularity). Ties/no-data keep the API order (stable
  // sort), so a brand-new outlet still renders and offline simply falls back unsorted.
  const searchLower = searchQuery.trim().toLowerCase()
  const popularity = popularityQuery.data
  const visibleItems = useMemo(() => {
    const base = visibleMenuItems(items, orderedCategories, resolvedCategoryId, searchLower)
    if (activeCategoryId !== null || searchLower || !popularity || popularity.length === 0) {
      return base
    }
    const soldByItem = new Map(popularity.map((row) => [row.menuItemId, row.soldQty]))
    return [...base].sort(
      (a, b) => (soldByItem.get(b.id) ?? 0) - (soldByItem.get(a.id) ?? 0),
    )
  }, [items, orderedCategories, resolvedCategoryId, searchLower, activeCategoryId, popularity])

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

  // The bottom dock (SummaryBar, position:fixed) grows tall once the walk-in cart has items — coupon
  // + member + discount fields stack above the action row — far taller than a static pad. Measure it
  // so the menu scroll area always clears it and the last rows are never hidden behind the dock.
  const [dockHeight, setDockHeight] = useState(0)
  useEffect(() => {
    // Tablet+ only — the phone deck pads the catalog by a fixed CSS height instead (see the grid).
    if (isPhone) return
    const el = document.getElementById('pos-summary-dock')
    // Route the no-element case through `measure` too — a direct setState in the effect body trips
    // react-hooks' "setState synchronously within an effect"; going through the measure/observer
    // callback is the accepted pattern and preserves the exact behaviour (0 when the dock is absent).
    const measure = () => {
      // While the deck is EXPANDED it is modal (its own scrim covers the grid), so re-padding the
      // catalog to 620px would only scroll-jump a surface nobody can see — and jump it back on
      // collapse. The grid always clears the PEEK height.
      if (el?.dataset.expanded === 'true') return
      setDockHeight(el?.offsetHeight ?? 0)
    }
    measure()
    if (!el) return
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [openBillId, lineCount, isPhone])

  const discountMinor = parseDiscountInput(discountInput, currency)
  const clientSubtotalMinor = cart.reduce(
    (sum, l) => sum + l.effectiveUnitPriceMinor * l.qty,
    0,
  )
  // Offline (Phase 5, ADR 0028): the live quote can only fail, so it is disabled (enabledOverride
  // below) and a provisional breakdown is computed locally from the cached effective-rules instead
  // — converted to the SAME PriceBreakdownResponse shape so every downstream renderer (SummaryBar,
  // PaymentModal's ModalBreakdown, the receipt) needs no offline-specific branch of its own.
  const quoteQuery = useQuote(
    session,
    cartLines,
    discountMinor,
    couponCode,
    attachedMember?.id ?? null,
    loyaltyRedeemPoints,
    !offline,
  )
  const provisionalBreakdown =
    offline && effectiveRules && cart.length > 0
      ? computeProvisionalPricing(clientSubtotalMinor, effectiveRules, {
          fixedDiscountMinor: discountMinor > 0 ? discountMinor : null,
})
      : null
  // An EMPTY cart shows no breakdown and a zero total, whichever cache still holds a figure. The
  // live quote masks itself (useQuote → visibleQuote), but a RESUMED parked order carries its own
  // breakdown and total, and neither is cleared when the cashier takes its lines off one by one —
  // so without this gate the deck kept showing the parked order's Rp 45.000 under an empty list,
  // the same stale-total bug through a second door.
  const cartEmpty = cart.length === 0
  const breakdown: PriceBreakdownResponse | null = offline
    ? provisionalBreakdown
      ? toDisplayBreakdown(provisionalBreakdown)
      : null
    : cartEmpty
      ? null
      : (quoteQuery.data ?? resumedOrder?.breakdown ?? null)
  const grandTotalMinor = cartEmpty
    ? 0
    : (breakdown?.grandTotalMinor ?? resumedOrder?.totalMinor ?? clientSubtotalMinor)
  // The redemption ceiling: the member's balance, capped by the total due BEFORE this redemption
  // (grandTotalMinor already has any currently-committed redemption subtracted — add it back so
  // the cap doesn't shrink itself as points are applied; loyaltyRedeemedMinor is 0 until the quote
  // resolves, which is a safe/conservative starting bound). Offline: points redemption is disabled
  // entirely (server 422s a redemption on an offline replay) — the ceiling is forced to 0.
  const maxRedeemablePoints =
    !offline && attachedMember
      ? Math.max(
          0,
          Math.min(attachedMember.pointsBalance, grandTotalMinor + (breakdown?.loyaltyRedeemedMinor ?? 0)),
        )
      : 0

  // Phase 6 (ADR 0029): feed the customer-facing display. `displayPublisher` itself no-ops until a
  // display has actually been opened this session, so calling this costs nothing beyond a re-render
  // for the common terminal that never opens one — see displayPublisher.ts.
  //
  // Bug fix: also called directly (not just from the effect below) when PaymentModal closes WITHOUT
  // a completed payment (cancel/back) — opening the modal publishes PAYMENT_STARTED ("amount due"),
  // and nothing else re-publishes the cart afterwards since the cart itself didn't change, so the
  // display would otherwise keep showing "please pay" until the cashier next mutates the cart.
  function publishCurrentDisplayState() {
    if (!displayPublisher.isOpened) return
    if (activeBill) {
      if (activeBill.lineCount > 0) {
        // carries no per-line detail (see its class doc) — the display shows
        // the running total only while a bill is open, no itemised lines.
        displayPublisher.publishCartUpdated([], billDisplayBreakdown(activeBill))
      } else {
        displayPublisher.publishIdle()
      }
      return
    }
    if (lineCount === 0) {
      displayPublisher.publishIdle()
      return
    }
    displayPublisher.publishCartUpdated(
      cartDisplayLines(cart, items),
      cartDisplayBreakdown(breakdown, { clientSubtotalMinor, grandTotalMinor, currency }),
    )
  }

  useEffect(() => {
    publishCurrentDisplayState()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [displayPublisher, activeBill, lineCount, cart, items, breakdown, clientSubtotalMinor, grandTotalMinor, currency])

  const discountInvalid =
    discountInput !== '' && (isNaN(Number(discountInput)) || Number(discountInput) < 0)

  const activeTableLabel = activeBill?.tableId
    ? (tables.find((tbl) => tbl.tableId === activeBill.tableId)?.label ?? null)
    : null

  const totalBills = openBillsList.length

  // ── The walk-in deck (phone, Native Till Android v2) ────────────────────────────────────────
  // BillDock renders strings, never numbers: every money value is formatted here through
  // formatMoney (rule 9). Bill mode has its own mapping inside BillDetail — same component, other
  // data owner.
  const walkInDockLines: DockLine[] = cart.map((l) => {
    const key = lineKey(l.menuItemId, l.selectedOptionIds)
    const name = items.find((i) => i.id === l.menuItemId)?.name ?? ''
    return {
      key,
      name:
        l.selectedOptionNames.length > 0
          ? `${name} · ${l.selectedOptionNames.join(', ')}`
          : name,
      unitLabel: t('posShell.dock.lineUnit', {
        qty: l.qty,
        price: formatMoney(l.effectiveUnitPriceMinor, currency, locale),
      }),
      totalLabel: formatMoney(l.effectiveUnitPriceMinor * l.qty, currency, locale),
      qty: l.qty,
      paid: false,
      selectable: false,
      selected: false,
      // The walk-in cart is a client array until Charge — anyone ringing may trim it. The
      // owner/manager lockdown is a BILL rule (billPermissions), not a cart one.
      canRemove: true,
      onInc: () => changeCartQty(key, 1),
      onDec: () => changeCartQty(key, -1),
    }
  })

  // The chips only exist while the deck is expanded, so they need only scroll their field into
  // view — the coupon / member / manual-discount stack lives in the deck's `extras` slot.
  function revealDockField(anchor: 'discount' | 'member') {
    document.getElementById(`pos-dock-${anchor}`)?.scrollIntoView({ block: 'nearest' })
  }

  // Keyed lookup rather than a ternary: dockActions owns WHICH chips appear, and a key it grows
  // later must fail loudly here (undefined → filtered out) instead of silently rendering as Member.
  const WALK_IN_CHIPS: Partial<Record<string, Omit<DockAction, 'key'>>> = {
    discount: {
      icon: <Percent className="size-[15px]" aria-hidden="true" />,
      label: t('posShell.dock.action.discount'),
      active: discountMinor > 0,
      onClick: () => revealDockField('discount'),
    },
    member: {
      icon: <UserRound className="size-[15px]" aria-hidden="true" />,
      label: t('posShell.dock.action.member'),
      active: attachedMember != null,
      onClick: () => revealDockField('member'),
    },
  }
  const walkInDockActions: DockAction[] = dockActions({
    isBill: false,
    unpaidCount: cart.length,
    canManualDiscount,
  }).flatMap((key) => {
    const chip = WALK_IN_CHIPS[key]
    return chip ? [{ key, ...chip }] : []
  })

  const walkInDueText = formatMoney(grandTotalMinor, currency, locale)

  // In bill mode the tile badge counts the OPEN BILL's unpaid lines, never the local cart. The
  // count comes from BillDetail (onUnpaidQtyByItem) because BillSummaryResponse has no per-item
  // breakdown; before the deck, bill mode simply showed no badge at all.
  function tileQty(menuItemId: string): number {
    if (openBillId) return billQty.billId === openBillId ? (billQty.byItem[menuItemId] ?? 0) : 0
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

  // Walk-in cart line edits (WalkInCartSheet). Pure local-state — the cart is a client array until
  // Charge, so no backend call. `delta` of -1 at qty 1 drops the line (decrement-to-remove).
  function changeCartQty(key: string, delta: number) {
    setCart((prev) => {
      const idx = prev.findIndex((l) => lineKey(l.menuItemId, l.selectedOptionIds) === key)
      if (idx === -1) return prev
      const nextQty = prev[idx].qty + delta
      if (nextQty <= 0) return prev.filter((_, i) => i !== idx)
      const next = [...prev]
      next[idx] = { ...next[idx], qty: nextQty }
      return next
    })
  }

  /**
   * The dock's Pay, shared by the phone deck and the tablet summary bar — both entry points (walk-in
   * cart and bill mode) must pass the same two gates in the same order.
   */
  function handleDockPay() {
    // ADR 0049 P3b operator gate — checked FIRST, before the register-open gate below (a
    // cashier identifies themselves before anything else happens at the till).
    if (operatorSignInRequired(isDeviceTerminal, operatorSession.operator)) {
      setShowOperatorPinSheet(true)
      return
    }
    // Payment gate (owner request "open the register first"): online + no confirmed open
    // session → redirect to the RegisterSheet instead of proceeding. Loading/error states
    // fail OPEN (let the sale proceed) — see registerGate.ts.
    if (
      noConfirmedOpenSession({
        offline,
        isLoading: registerSessionQuery.isLoading,
        isError: registerSessionQuery.isError,
        session: registerSessionQuery.data,
      })
    ) {
      setRegisterGateActive(true)
      setShowRegisterSheet(true)
      return
    }
    if (openBillId) {
      // P4: Pay pays — BillDetail opens its pay modal directly (full unpaid check).
      setAutoPayToken((k) => k + 1)
    } else {
      setModal('payment')
    }
  }

  function removeCartLineByKey(key: string) {
    setCart((prev) => prev.filter((l) => lineKey(l.menuItemId, l.selectedOptionIds) !== key))
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
    setPlacedProvisional(false)
    setLastAppliedPromotions(breakdown?.appliedPromotions ?? [])
    displayPublisher.publishPaymentCompleted({
      amountMinor: payment.changeMinor ?? 0,
      currency: payment.currency,
})
    clearCart()
    setModal('receipt')
  }

  /**
   * Phase 5 (ADR 0028): the sale was durably enqueued (never POSTed) — build a client-side receipt
   * from the cart + provisional breakdown already computed above. Line names come from `items`
   * (already resolved for the tile grid); per-modifier price deltas are folded into
   * `effectiveUnitPriceMinor` already, so the receipt itemizes modifier NAMES only (no delta) —
   * an acceptable simplification for a provisional receipt, corrected once the sale syncs.
   */
  function handleOfflineSuccess(row: SaleQueueRow, tenderedMinor: number, changeMinor: number) {
    const cartAtSubmit = cart
    const order: OrderResponse = {
      orderId: row.idempotencyKey,
      businessId: session.businessId,
      totalMinor: row.provisional.grandTotalMinor,
      currency: row.provisional.currency,
      saleId: null,
      lines: cartAtSubmit.map((l) => ({
        menuItemId: l.menuItemId,
        name: items.find((i) => i.id === l.menuItemId)?.name ?? l.menuItemId,
        unitPriceMinor: l.effectiveUnitPriceMinor,
        qty: l.qty,
        lineTotalMinor: l.effectiveUnitPriceMinor * l.qty,
        modifiers: l.selectedOptionNames.map((name) => ({
          optionId: '',
          nameSnapshot: name,
          priceDeltaMinor: 0,
})),
})),
      payment: null,
      breakdown: toDisplayBreakdown(row.provisional),
      status: 'COMPLETED',
      orderType,
      tableId: null,
}
    const payment: PaymentResponse = {
      paymentId: row.idempotencyKey,
      orderId: order.orderId,
      tenderType: 'CASH',
      status: 'CAPTURED',
      amountMinor: row.provisional.grandTotalMinor,
      currency: row.provisional.currency,
      tenderedMinor,
      changeMinor,
      providerPending: false,
      saleId: null,
}
    setPlacedOrder(order)
    setPlacedPayment(payment)
    setPlacedProvisional(true)
    setLastAppliedPromotions([])
    displayPublisher.publishPaymentCompleted({ amountMinor: changeMinor, currency: row.provisional.currency })
    clearCart()
    setModal('receipt')
  }

  function handleNewOrder() {
    setPlacedOrder(null)
    setPlacedPayment(null)
    setPlacedProvisional(false)
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

      {/* ── 1a. Phone identity band (52px white — Native Till Android v2) ────── */}
      {isPhone ? (
        <PosPhoneHeader
          businessName={session.name}
          outletPicker={<OutletPicker variant="subtitle" />}
          // Who is ringing. On a device terminal that is the signed-in operator (ADR 0049 P3b);
          // on an ordinary login it is the principal's own username, which needs no translation.
          identity={
            operatorSession.operator
              ? t('posShell.identity', {
                  role: operatorSession.operator.role,
                  name: operatorSession.operator.displayName,
                })
              : auth.actor
          }
          offline={offline}
          queuedCount={queuedCount + rejectedCount}
          onConnectionClick={() => setShowSyncCenter(true)}
          parkedCount={parkedCount}
          onParkedClick={() => setShowParkedTray(true)}
          parkedDisabled={offline}
          parkedDisabledTitle={t('offline.disabled.parked')}
          onOverflowClick={() => setShowTillMenu((v) => !v)}
          overflowOpen={showTillMenu}
        />
      ) : null}

      {/* ── 1b. Status bar (56px ink band — redesign P4; tablet and up) ──────── */}
      {isPhone ? null : (
      <PosStatusBar
        businessName={session.name}
        outletPicker={<OutletPicker />}
        offline={offline}
        queuedCount={queuedCount + rejectedCount}
        onConnectionClick={() => setShowSyncCenter(true)}
        pinned={[
          {
            key: 'tables',
            icon: <Table2 className="size-4" aria-hidden="true" />,
            label: t('bills.floorTitle'),
            onClick: () => setShowTableFloor(true),
            disabled: offline,
            disabledTitle: t('offline.disabled.tableFloor'),
            badge: totalBills > 0 ? { count: totalBills, tone: 'brand' } : null,
            testId: 'pos-tables',
          },
          {
            key: 'parked',
            icon: <ClipboardList className="size-4" aria-hidden="true" />,
            label: t('pos.parked.trayTitle'),
            onClick: () => setShowParkedTray(true),
            disabled: offline,
            disabledTitle: t('offline.disabled.parked'),
            badge: parkedCount > 0 ? { count: parkedCount, tone: 'warning' } : null,
            testId: 'pos-parked',
          },
          printerStatusAction,
        ]}
        onOverflowClick={() => setShowTillMenu((v) => !v)}
        overflowOpen={showTillMenu}
        operator={isDeviceTerminal ? operatorSession.operator : null}
        showOperatorSignIn={isDeviceTerminal && !operatorSession.operator}
        onOperatorSignInClick={() => setShowOperatorPinSheet(true)}
      />
      )}

      {/* ── 2. Bill context strip (64px) — Walk-in tab + open-bill tabs ───────
          Tablet and up only. On a phone the deck's own title IS the ticket's identity, and tapping
          it opens the same order switcher this strip's selector did — 64px back to the catalog. */}
      {isPhone ? null : (
      <BillTabsBar
        bills={openBillsList}
        activeBillId={openBillId}
        locale={locale}
        offline={offline}
        walkInCount={lineCount}
        walkInTotalMinor={grandTotalMinor}
        currency={currency}
        onWalkInClick={() => {
          setOpenBillId(null)
          setBillSheetOpen(false)
        }}
        onTabClick={handleTabClick}
        onNewBill={() => setShowOpenBillDialog(true)}
        onSelectorClick={() => setShowBillSelector(true)}
      />
      )}

      {/* ── 2b. Phone search + floor row (Native Till Android v2) ─────────────
          The search field belongs WITH the catalog it filters, and "Tables" belongs beside it:
          both are ways of choosing what the next tap lands on. Kept out of the scroll area so a
          cashier hunting an item never has to scroll back up to the box. */}
      {isPhone ? (
        <div className="flex shrink-0 items-center gap-2.5 border-b border-line bg-surface px-3.5 py-2">
          <div className="relative min-w-0 flex-1">
            <svg
              className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-ink-3"
              width="17" height="17" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" strokeWidth="1.9" strokeLinecap="round"
              aria-hidden="true"
            >
              <circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" />
            </svg>
            <input
              type="search"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={t('bills.searchItems', { count: items.length })}
              aria-label={t('bills.searchItems', { count: items.length })}
              className="h-11 w-full rounded-xl bg-hover pl-10 pr-3 text-sm text-ink placeholder:text-ink-3 focus:outline-2 focus:outline-offset-[-2px] focus:outline-emerald"
            />
          </div>
          <button
            type="button"
            onClick={() => setShowTableFloor(true)}
            disabled={offline}
            data-testid="pos-tables"
            title={offline ? t('offline.disabled.tableFloor') : undefined}
            className="flex h-11 shrink-0 items-center gap-1.5 rounded-xl border border-line bg-surface px-3 text-sm font-semibold text-ink transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald disabled:cursor-not-allowed disabled:opacity-40"
          >
            <Table2 className="size-4" aria-hidden="true" />
            {t('bills.floorTitle')}
          </button>
        </div>
      ) : null}

      {/* ── Body: category rail + menu grid ─────────────────────────────────── */}
      <div className="relative flex min-h-0 flex-1 overflow-hidden">

        {/* ── 3. Category rail (104px, hidden <560px) ───────────────────── */}
        <nav
          aria-label={t('pos.categories')}
          className="hidden w-[88px] shrink-0 flex-col overflow-y-auto border-r border-line bg-surface pt-2 sm:flex"
        >
          {/* "All" cell */}
          <CategoryCell
            label={t('pos.category.all', 'All')}
            icon={<AllCategoriesIcon />}
            active={activeCategoryId === null}
            onClick={() => {
              setActiveCategoryId(null)
              setSearchQuery('')
            }}
          />
          {orderedCategories.map((cat) => (
            <CategoryCell
              key={cat.id}
              label={displayCategoryName(cat.name, t)}
              icon={<CategoryIcon name={cat.name} />}
              active={cat.id === activeCategoryId}
              onClick={() => {
                setActiveCategoryId(cat.id)
                setSearchQuery('')
              }}
            />
          ))}
        </nav>

        {/* ── Phone category chips ────────────────────────────────────────
            In normal flow under the search row, not floating over the grid: the deck now owns the
            bottom of the screen, so every pixel of the catalog's own scroll area has to be real. */}
        <div
          aria-label={t('pos.categories')}
          className="absolute top-0 left-0 right-0 z-10 flex h-[52px] items-center gap-2 overflow-x-auto border-b border-line bg-surface px-3.5 sm:hidden [-ms-overflow-style:none] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
        >
          <button
            type="button"
            aria-pressed={activeCategoryId === null && !searchQuery}
            onClick={() => { setActiveCategoryId(null); setSearchQuery('') }}
            className={cn(
              'h-10 shrink-0 rounded-full px-4 text-sm font-semibold transition-all focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
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
                'h-10 shrink-0 rounded-full px-4 text-sm font-semibold transition-all focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                cat.id === resolvedCategoryId && activeCategoryId !== null
                  ? 'bg-emerald text-on-emerald'
                  : 'border border-line bg-surface text-ink-2 hover:border-emerald-line hover:bg-emerald-tint',
              )}
            >
              {displayCategoryName(cat.name, t)}
            </button>
          ))}
        </div>

        {/* ── 4. Menu grid ─────────────────────────────────────────────── */}
        <div
          className={cn(
            'min-h-0 flex-1 overflow-y-auto px-5 pb-28 pt-3 sm:pb-28',
            // Phone: pad by the deck's own (CSS-known) peek height rather than by measurement. The
            // deck is rendered by EITHER Pos or BillDetail, and BillDetail's mounts a beat late —
            // it shows a skeleton while the bill loads — so a ResizeObserver installed when
            // `openBillId` changes finds nothing and the last tile row would sit under the deck.
            'max-sm:pb-[188px] max-sm:[@media(min-height:720px)]:pb-[252px]',
          )}
          style={!isPhone && dockHeight ? { paddingBottom: dockHeight + 24 } : undefined}
        >
          {/* Phone: spacer for the pinned chip row */}
          <div className="h-[52px] sm:hidden" aria-hidden="true" />

          {/* Catalog search — lives WITH the catalog it filters (redesign P4), not in the chrome.
              Phone has its own copy in the header row above, beside the Tables button. */}
          <div className="relative mb-3 hidden max-w-md sm:block">
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

          {menuQuery.isLoading ? (
            <MenuSkeleton />
          ) : items.length === 0 ? (
            <EmptyMenu />
          ) : (
            <div>
              {/* Section label */}
              {!searchQuery && orderedCategories.length > 0 ? (
                <div className="mb-3 text-2xs font-bold uppercase tracking-eyebrow text-ink-3">
                  {orderedCategories.find((c) => c.id === resolvedCategoryId)?.name ?? t('pos.category.all', 'All')}
                </div>
              ) : null}

              {/* Empty category tab → a designed explanation, never a silent void. */}
              {!searchLower && visibleItems.length === 0 && orderedCategories.length > 0 ? (
                <EmptyCategory
                  name={
                    orderedCategories.find((c) => c.id === resolvedCategoryId)?.name ??
                    t('pos.category.all', 'All')
                  }
                  canManage={hasAnyRole(auth.roles, 'owner', 'manager')}
                />
              ) : (
                /* Responsive grid. Phone is a FIXED 2 columns: the old `minmax(156px, 1fr)` auto-fill
                   collapsed to 1 column on a 360px S23 (px-5 leaves 320px; 2×156 + gap = 324 > 320).
                   From md → 3, from 1180px → 4 (mirrors the previous breakpoints). */
                <div className="grid grid-cols-2 gap-3 md:grid-cols-3 min-[1180px]:grid-cols-4">
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
              )}
            </div>
          )}
        </div>
      </div>

      {/* ── 5a. The walk-in deck (phone) — always attached, never "opened" ──────
          Mounted only for the walk-in cart: with a bill open, BillDetail renders the deck instead
          (it owns the bill's lines and their mutations), so the two never stack. Present even on an
          empty cart — a deck you have to summon is the thing this redesign removes. */}
      {isPhone && !openBillId ? (
        <BillDock
          title={t('posShell.walkInSale')}
          meta={t('bills.lineCount', { n: lineCount })}
          expanded={dockExpanded}
          onExpandedChange={setDockExpanded}
          lines={dockExpanded ? walkInDockLines : peekLines(walkInDockLines)}
          emptyHint={t('posShell.dock.emptyCart')}
          actions={walkInDockActions}
          breakdown={lineCount > 0 ? breakdown : null}
          promotions={breakdown?.appliedPromotions ?? []}
          extras={
            lineCount > 0 ? (
              <div className="flex flex-col gap-2.5 pt-1">
                <div id="pos-dock-discount" className="flex flex-col gap-2.5">
                  <CouponField
                    code={couponCode}
                    status={breakdown?.couponStatus ?? null}
                    onApply={setCouponCode}
                    onClear={() => setCouponCode(null)}
                    disabled={offline}
                  />
                  {offline ? <OfflineHint text={t('offline.disabled.coupon')} /> : null}
                  {canManualDiscount ? (
                    <div className="flex items-center gap-2">
                      <label htmlFor="pos-discount" className="shrink-0 text-sm font-medium text-ink-2">
                        {t('pos.addDiscount')}
                      </label>
                      <input
                        id="pos-discount"
                        type="number"
                        min="0"
                        step="any"
                        value={discountInput}
                        onChange={(e) => setDiscountInput(e.target.value)}
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
                <div id="pos-dock-member" className="flex flex-col gap-2.5">
                  <MemberField
                    session={session}
                    currency={currency}
                    locale={locale}
                    member={attachedMember}
                    onAttach={setAttachedMember}
                    onClear={() => {
                      setAttachedMember(null)
                      setLoyaltyRedeemPoints(0)
                    }}
                    redeemPoints={offline ? 0 : loyaltyRedeemPoints}
                    maxRedeemable={maxRedeemablePoints}
                    onRedeemChange={setLoyaltyRedeemPoints}
                    disabled={offline}
                  />
                  {offline ? <OfflineHint text={t('offline.disabled.member')} /> : null}
                </div>
              </div>
            ) : null
          }
          currency={currency}
          locale={locale}
          dueLabel={t(dueLabelKey({ splitMode: false, hasPaidLines: false }))}
          dueText={walkInDueText}
          totalPending={!offline && quoteQuery.refreshing}
          payLabel={t('posShell.chargeAmount', { amount: walkInDueText })}
          payDisabled={lineCount === 0}
          onPay={handleDockPay}
          // "Clear order" — WalkInCartSheet had it and the deck had dropped it, leaving a five-line
          // mis-ring five taps from empty. No manager gate: the cart is a client array until Charge.
          cancel={
            lineCount > 0
              ? {
                  canCancel: true,
                  hintVisible: false,
                  hint: '',
                  label: t('pos.clearCart'),
                  onCancel: clearCart,
                }
              : null
          }
        />
      ) : null}

      {/* ── 5b. Bottom summary bar (96px, rounded-t-sheet) — tablet and up ──── */}
      {/* Only shown when there is an active bill OR items in the immediate cart */}
      {!isPhone && (activeBill || lineCount > 0) ? (
        <SummaryBar
          activeBill={activeBill ?? null}
          lineCount={lineCount}
          grandTotalMinor={grandTotalMinor}
          // Walk-in only: bills carry their own server-side running total. Offline totals are
          // provisional-computed locally and never lag the cart.
          totalPending={!offline && !activeBill && quoteQuery.refreshing}
          currency={currency}
          locale={locale}
          discountInput={discountInput}
          discountInvalid={discountInvalid}
          onDiscountChange={setDiscountInput}
          showDiscountInput={canManualDiscount}
          offline={offline}
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
          loyaltyRedeemPoints={offline ? 0 : loyaltyRedeemPoints}
          maxRedeemablePoints={maxRedeemablePoints}
          onLoyaltyRedeemChange={setLoyaltyRedeemPoints}
          onExpand={() => setBillSheetOpen(true)}
          onExpandCart={() => setModal('cart')}
          onDestinationClick={() => setShowBillSelector(true)}
          onSend={() => {
            // ADR 0049 P3b: a device terminal with no signed-in operator must identify one BEFORE
            // the kitchen ticket fires (it attributes the sale too) — FAIL CLOSED, mirroring the
            // eventual P4 backend guard. A no-op for a normal `user` login (isDeviceTerminal false).
            if (operatorSignInRequired(isDeviceTerminal, operatorSession.operator)) {
              setShowOperatorPinSheet(true)
              return
            }
            // P4: Send SENDS — the kitchen ticket fires directly; the sheet stays collapsed
            // (the KOT and payment overlays render sheet-independently inside BillDetail).
            setAutoKotToken((k) => k + 1)
          }}
          onPay={handleDockPay}
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

      {modal === 'cart' ? (
        <WalkInCartSheet
          cart={cart}
          items={items}
          currency={currency}
          locale={locale}
          onInc={(key) => changeCartQty(key, 1)}
          onDec={(key) => changeCartQty(key, -1)}
          onRemove={removeCartLineByKey}
          onClear={() => {
            clearCart()
            setModal(null)
          }}
          onClose={() => setModal(null)}
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
          onClose={() => {
            setModal(null)
            // Cancel/back without a completed payment — revert the customer display off
            // "amount due" back to the live cart (or idle), since nothing else will (bug fix).
            publishCurrentDisplayState()
          }}
          parkedOrderId={resumedOrder?.orderId ?? null}
          orderType={orderType}
          tableId={null}
          offline={offline}
          onOfflineSuccess={handleOfflineSuccess}
          displayPublisher={displayPublisher}
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
          provisional={placedProvisional}
          // Manager-gated Return (ADR 0061). Never on a PROVISIONAL offline receipt — its payment is
          // a client-side placeholder with no server-side sale to reverse yet.
          secondaryAction={
            !placedProvisional && canReturnSale && canReturnPayment(placedPayment)
              ? { label: t('pos.return.action'), onClick: () => setShowReturnDialog(true) }
              : undefined
          }
          onNew={handleNewOrder}
        />
      ) : null}

      {modal === 'receipt' && showReturnDialog && placedOrder && placedPayment ? (
        <ReturnSaleDialog
          session={session}
          order={placedOrder}
          payment={placedPayment}
          locale={locale}
          onClose={() => setShowReturnDialog(false)}
          onReturned={() => {
            // Sale reversed — close the dialog and the receipt (back to a fresh POS).
            setShowReturnDialog(false)
            handleNewOrder()
          }}
        />
      ) : null}

      {showTillMenu ? (
        <TillMenuSheet
          onClose={() => setShowTillMenu(false)}
          items={[
            // Native Till Android v2: the phone header pins nothing but Incoming, so the three
            // affordances the ink band used to carry — leaving the till, printer status, and the
            // operator sign-in — arrive here instead. On tablet+ they stay in the band and these
            // rows are not added, so the menu never doubles up.
            ...(isPhone
              ? ([
                  {
                    // The order switcher (walk-in / bills / floor / parked). It used to be a button
                    // on the deck's handle, where it competed with the ticket's name for the tap that
                    // means "show me my order"; here it is deliberately one level down.
                    key: 'orders',
                    icon: <ClipboardList className="size-4" aria-hidden="true" />,
                    label: t('posShell.switcherTitle'),
                    onSelect: () => setShowBillSelector(true),
                  },
                  {
                    key: 'leave-till',
                    icon: <ArrowLeft className="size-4" aria-hidden="true" />,
                    label: t('posShell.leaveTill'),
                    // Back is an ACTION, not a destination (ADR 0075 rule N1) — the same intent
                    // BackButton resolves, reused rather than re-derived. `/` is only the fallback
                    // for a cold start straight into /pos.
                    onSelect: () => {
                      const intent = backIntentFor(window.history.state)
                      if (intent.kind === 'pop') navigate(intent.delta)
                      else navigate('/', { replace: true })
                    },
                  },
                  toTillMenuItem(printerStatusAction),
                  ...(isDeviceTerminal && !operatorSession.operator
                    ? [
                        {
                          key: 'operator-signin',
                          icon: <KeyRound className="size-4" aria-hidden="true" />,
                          label: t('posShell.operatorSignIn'),
                          onSelect: () => setShowOperatorPinSheet(true),
                        },
                      ]
                    : []),
                ] satisfies TillMenuItem[])
              : []),
            {
              key: 'register',
              icon: <Banknote className="size-4" aria-hidden="true" />,
              label: registerMenuLabel,
              onSelect: () => setShowRegisterSheet(true),
              // ADR 0028: never close a drawer offline or over an unsynced queue — expected cash
              // would understate the replayed sales.
              disabled: offline || queuedCount + rejectedCount > 0,
              disabledTitle: t('register.disabledOffline'),
            },
            {
              key: 'stocktake',
              icon: <ClipboardCheck className="size-4" aria-hidden="true" />,
              label: t('stocktake.tillMenuLabel'),
              onSelect: () => setShowStocktakeSheet(true),
              // ADR 0038 phase 3: the stocktake submits directly to the server (no offline queue
              // support) — same posture as the register close.
              disabled: offline,
              disabledTitle: t('offline.disabled.stocktake'),
            },
            {
              key: 'ingredients',
              icon: <Package className="size-4" aria-hidden="true" />,
              label: t('inventory.tillMenuLabel'),
              // A route, not a sheet — /inventory is the stock-item catalog behind the opname
              // (ADR 0046). Navigation unmounts the POS; nothing to clean up here.
              onSelect: () => navigate('/inventory'),
              disabled: offline,
              disabledTitle: t('inventory.loadError'),
            },
            {
              key: 'history',
              icon: <History className="size-4" aria-hidden="true" />,
              label: t('pos.history.tillMenuLabel'),
              onSelect: () => setShowSalesHistory(true),
              // Server-truth read: the list cannot answer offline (queued sales live in the
              // SyncCenter until they replay).
              disabled: offline,
              disabledTitle: t('pos.history.disabledOffline'),
            },
            {
              key: 'summary',
              icon: <FileText className="size-4" aria-hidden="true" />,
              label: t('register.summaryTillMenuLabel'),
              // No explicit id → DailySummary resolves the current OPEN (X-report) or last CLOSED
              // (Z-report) session. Server-truth aggregate — unavailable offline.
              onSelect: () => {
                setSummarySessionId(null)
                setShowSummary(true)
              },
              disabled: offline,
              disabledTitle: t('register.disabledOffline'),
            },
            // Owner/manager only: browse PAST closed days' sales (each drills into its Z-report and
            // that day's transactions). Server-truth read — unavailable offline.
            ...(canViewClosingHistory
              ? [
                  {
                    key: 'closing-history',
                    icon: <CalendarClock className="size-4" aria-hidden="true" />,
                    label: t('pos.closingHistory.tillMenuLabel'),
                    onSelect: () => setShowClosingHistory(true),
                    disabled: offline,
                    disabledTitle: t('pos.closingHistory.disabledOffline'),
                  },
                ]
              : []),
            // Customer display needs a SECOND window (a browser tab dragged to the customer
            // monitor). The Android shell is a single WebView — window.open would either navigate
            // the till itself away mid-shift or spawn a logged-out external tab — so the entry is
            // browser-only.
            ...(isNativeShell()
              ? []
              : [
                  {
                    key: 'display',
                    icon: <Monitor className="size-4" aria-hidden="true" />,
                    label: t('pos.customerDisplay.button'),
                    onSelect: () => {
                      displayPublisher.activate()
                      window.open(
                        `${window.location.origin}/pos/customer-display?outlet=${encodeURIComponent(session.businessId)}`,
                        'native-pos-display',
                      )
                    },
                  },
                ]),
            {
              key: 'giftcard',
              icon: <Gift className="size-4" aria-hidden="true" />,
              label: t('pos.loyalty.giftCard.sellTitle'),
              onSelect: () => setShowGiftCardSell(true),
              disabled: offline,
              disabledTitle: t('offline.disabled.giftCard'),
            },
            { key: 'kitchen', icon: <ChefHat className="size-4" aria-hidden="true" />, label: t('nav.kitchen'), to: '/kitchen' },
            { key: 'menu', icon: <BookOpen className="size-4" aria-hidden="true" />, label: t('nav.menu'), to: '/menu' },
            // The door to the employee self-service surface (/me is the always-available floor —
            // never role-gated, so a cashier who is also an employee can always reach their own
            // payslips/time-off/claims from the till). On phone /me carries the employee tab bar.
            // ADR 0049 P3b: dropped on a DEVICE terminal — the operator chip + sign-out (below)
            // already cover "who is ringing"; a device has no personal /me identity of its own.
            // KEPT for a normal `user` login (backward compat until the Employee app, P5).
            ...(isDeviceTerminal
              ? []
              : [{ key: 'me', icon: <UserRound className="size-4" aria-hidden="true" />, label: t('me.tillMenuLabel'), to: '/me' }]),
            // ADR 0049 P3b — the three device-terminal account actions (elevate / end-elevation /
            // log-out-outlet) plus the operator sign-out, all driven by ONE shared visibility gate
            // so this list and ServicePos.tsx's twin can never disagree on what a given login sees.
            ...(accountMenu.showElevateEntry
              ? [
                  {
                    key: 'elevate',
                    icon: <LogIn className="size-4" aria-hidden="true" />,
                    label: t('posShell.elevateEntry'),
                    onSelect: () => auth.elevate(),
                  },
                ]
              : []),
            ...(accountMenu.showOperatorSignOut
              ? [
                  {
                    key: 'operator-signout',
                    icon: <KeyRound className="size-4" aria-hidden="true" />,
                    label: t('operatorPin.tillMenuSignOut'),
                    onSelect: () => operatorSession.signOut(),
                  },
                ]
              : []),
            ...(accountMenu.showEndElevation
              ? [
                  {
                    key: 'end-elevation',
                    icon: <Undo2 className="size-4" aria-hidden="true" />,
                    label: t('posShell.endElevation'),
                    onSelect: () => auth.dropElevation(),
                  },
                ]
              : []),
            {
              key: 'theme',
              icon: theme === 'dark' ? <Sun className="size-4" aria-hidden="true" /> : <Moon className="size-4" aria-hidden="true" />,
              label: t('a11y.toggleTheme'),
              onSelect: toggle,
            },
            ...(accountMenu.showPlainLogout
              ? [
                  {
                    key: 'logout',
                    icon: <LogOut className="size-4" aria-hidden="true" />,
                    label: t('nav.logout'),
                    onSelect: auth.logout,
                    danger: true,
                  },
                ]
              : []),
            ...(accountMenu.showLogoutOutlet
              ? [
                  {
                    key: 'logout-outlet',
                    icon: <LogOut className="size-4" aria-hidden="true" />,
                    label: t('posShell.logoutOutlet'),
                    onSelect: auth.logout,
                    danger: true,
                  },
                ]
              : []),
          ]}
        />
      ) : null}

      {showOperatorPinSheet ? (
        <OperatorPinSheet
          session={session}
          requirePin={operatorRequirePin}
          onClose={() => setShowOperatorPinSheet(false)}
        />
      ) : null}

      {showRegisterSheet ? (
        <RegisterSheet
          session={session}
          currency={currency}
          locale={locale}
          reasonMessage={registerGateActive ? t('register.openBeforePay') : undefined}
          onClose={() => {
            setShowRegisterSheet(false)
            setRegisterGateActive(false)
            // After a successful open, land back where the cashier was — hit Pay again rather
            // than auto-opening the payment modal (the pay button itself re-checks the gate).
          }}
          onContinueToStocktake={
            // Same availability rule as the till-menu stocktake entry: no offline path (ADR 0038).
            offline
              ? undefined
              : () => {
                  setShowRegisterSheet(false)
                  setRegisterGateActive(false)
                  setShowStocktakeSheet(true)
                }
          }
          onPrintSummary={
            // Server-truth aggregate — no offline path, same as the close itself.
            offline
              ? undefined
              : (sessionId) => {
                  setSummarySessionId(sessionId)
                  setShowSummary(true)
                }
          }
        />
      ) : null}

      {showSummary ? (
        <DailySummary
          session={session}
          locale={locale}
          sessionId={summarySessionId}
          onClose={() => {
            setShowSummary(false)
            setSummarySessionId(null)
          }}
        />
      ) : null}

      {showStocktakeSheet ? (
        <StocktakeSheet
          session={session}
          currency={currency}
          locale={locale}
          onClose={() => setShowStocktakeSheet(false)}
        />
      ) : null}

      {showSalesHistory ? (
        <SalesHistorySheet session={session} locale={locale} onClose={() => setShowSalesHistory(false)} />
      ) : null}
      {showClosingHistory ? (
        <ClosingHistorySheet
          session={session}
          locale={locale}
          onClose={() => setShowClosingHistory(false)}
          onOpenStocktake={() => {
            // Stock-count correction hands off to the outlet's opname (self-compensating, ADR 0064).
            setShowClosingHistory(false)
            setShowStocktakeSheet(true)
          }}
        />
      ) : null}

      {showSyncCenter ? <SyncCenter locale={locale} onClose={() => setShowSyncCenter(false)} /> : null}

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
          autoKotToken={autoKotToken}
          autoPayToken={autoPayToken}
          canVoid={canVoidBill}
          onSwitchOrder={() => setShowBillSelector(true)}
          onUnpaidQtyByItem={(byItem) => setBillQty({ billId: openBillId, byItem })}
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
          walkInCount={lineCount}
          walkInTotalMinor={grandTotalMinor}
          currency={currency}
          onWalkIn={() => {
            setOpenBillId(null)
            setShowBillSelector(false)
            setBillSheetOpen(false)
          }}
          onOpenFloor={
            offline
              ? null
              : () => {
                  setShowBillSelector(false)
                  setShowTableFloor(true)
                }
          }
          onOpenParked={
            offline
              ? null
              : () => {
                  setShowBillSelector(false)
                  setShowParkedTray(true)
                }
          }
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
// Pure helpers
// ---------------------------------------------------------------------------

// (parseDiscountInput, deriveCategories, and lineKey moved to ./lib — redesign P1.)
