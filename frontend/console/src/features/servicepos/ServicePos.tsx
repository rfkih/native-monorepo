/**
 * ServicePos — the generic "primary item + add-ons" POS surface for service verticals (carwash
 * and barbershop today). A full-screen front-office terminal, structurally the counterpart of
 * features/pos/Pos.tsx but driven entirely by a VerticalPosConfig instead of hardcoded restaurant
 * concepts (menu items, modifiers, tables, bills).
 *
 * Flow: pick ONE primary item (package/service — tap replaces the previous pick) + any number of
 * add-ons (chips, each with its own qty stepper) → fill the location field (bay/chair, required
 * per config) + the vertical's extra fields (vehicle plate; staff attribution — REQUIRED for
 * barbershop) → a live price breakdown streams from useTicketQuote → Charge opens
 * ServicePaymentModal → on success, ServiceReceipt takes over; "New ticket" resets the terminal.
 *
 * Strings rule (rule 9): every label is an i18n key. Money rule (rule 8): all amounts are integer
 * minor units end to end, rendered via formatMoney().
 */
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import {
  ArrowLeft,
  Car,
  Gift,
  LogOut,
  Minus,
  Moon,
  Plus,
  Scissors,
  Settings,
  Store,
  Sun,
  X,
} from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Field, TextInput } from '@/components/ui/Field'
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
import type { VerticalPosConfig } from './config'
import {
  useCatalogPackages,
  useCatalogAddons,
  useStaffProfiles,
  useTicketQuote,
  type AppliedPromotionResponse,
  type CatalogItemResponse,
  type PriceBreakdownResponse,
  type StaffProfileResponse,
  type TicketLineInput,
  type TicketResponse,
} from './api'
import { ServicePaymentModal } from './ServicePaymentModal'
import { ServiceReceipt } from './ServiceReceipt'

const SELECT_CLASS =
  'h-[52px] w-full rounded-xl border border-line bg-surface px-4 text-[15px] text-ink transition-colors focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15'

// ---------------------------------------------------------------------------
// Root
// ---------------------------------------------------------------------------

export function ServicePos({ config }: { config: VerticalPosConfig }) {
  const { company } = useSession()
  if (!company) return <NoCompany />
  // The gate resolves a REAL outlet id (never the business-unit id — ADR 0012) of the required
  // vertical. key forces a full remount when the effective outlet changes, so all local ticket
  // state resets implicitly and never bleeds across outlets.
  return (
    <OutletGate company={company} requiredVertical={config.vertical}>
      {(session) => <ServicePosInner key={session.businessId} config={config} session={session} />}
    </OutletGate>
  )
}

// ---------------------------------------------------------------------------
// Inner
// ---------------------------------------------------------------------------

interface AddonLine {
  item: CatalogItemResponse
  qty: number
}

function ServicePosInner({ config, session }: { config: VerticalPosConfig; session: CompanySession }) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const auth = useAuth()
  // The manual discount is owner/manager-only (ADR 0026 §5; the server 403s anyway — this hides the
  // input for a cashier so it never sees an affordance it cannot use).
  const canManualDiscount = hasAnyRole(auth.roles, 'owner', 'manager')

  const packagesQuery = useCatalogPackages(config, session)
  const addonsQuery = useCatalogAddons(config, session)
  const staffQuery = useStaffProfiles(config, session)

  const packages = (packagesQuery.data ?? []).filter((p) => p.active)
  const addons = (addonsQuery.data ?? []).filter((a) => a.active)
  const staffProfiles = (staffQuery.data ?? []).filter((s) => s.active)

  // Ticket-building state
  const [selectedPackage, setSelectedPackage] = useState<CatalogItemResponse | null>(null)
  const [addonLines, setAddonLines] = useState<Map<string, AddonLine>>(new Map())
  const [bay, setBay] = useState('')
  const [vehiclePlate, setVehiclePlate] = useState('')
  const [staffProfileId, setStaffProfileId] = useState<string | null>(null)
  const [discountInput, setDiscountInput] = useState('')
  // Phase 3 (ADR 0026): the committed coupon code fed into the live quote + checkout.
  const [couponCode, setCouponCode] = useState<string | null>(null)
  // Phase 4 (ADR 0027): the attached loyalty member + committed points redemption, fed into the
  // live quote + checkout.
  const [attachedMember, setAttachedMember] = useState<MemberResponse | null>(null)
  const [loyaltyRedeemPoints, setLoyaltyRedeemPoints] = useState<number>(0)
  const [showGiftCardSell, setShowGiftCardSell] = useState(false)

  // Overlay state
  const [modal, setModal] = useState<'payment' | 'receipt' | null>(null)
  const [ticket, setTicket] = useState<TicketResponse | null>(null)
  // The applied-promotion detail from the LAST live quote before payment — the checkout response
  // itself carries only the aggregate discount (ADR 0026), so the receipt uses this snapshot.
  const [lastAppliedPromotions, setLastAppliedPromotions] = useState<AppliedPromotionResponse[]>([])

  const currency = packages[0]?.currency ?? addons[0]?.currency ?? session.baseCurrency

  const lines: TicketLineInput[] = useMemo(() => {
    const out: TicketLineInput[] = []
    if (selectedPackage) {
      out.push({ itemType: config.primaryItemType, itemId: selectedPackage.id, qty: 1 })
    }
    for (const { item, qty } of addonLines.values()) {
      out.push({ itemType: 'ADDON', itemId: item.id, qty })
    }
    return out
  }, [config.primaryItemType, selectedPackage, addonLines])

  const discountMinor = parseDiscountInput(discountInput, currency)
  const quoteQuery = useTicketQuote(
    config,
    session,
    lines,
    discountMinor,
    couponCode,
    attachedMember?.id ?? null,
    loyaltyRedeemPoints,
  )
  const breakdown = quoteQuery.data ?? null

  const clientSubtotalMinor =
    (selectedPackage?.priceMinor ?? 0) +
    [...addonLines.values()].reduce((sum, l) => sum + l.item.priceMinor * l.qty, 0)
  const grandTotalMinor = breakdown?.grandTotalMinor ?? Math.max(0, clientSubtotalMinor - discountMinor)
  // The redemption ceiling — see features/pos/Pos.tsx's twin computation doc.
  const maxRedeemablePoints = attachedMember
    ? Math.max(
        0,
        Math.min(attachedMember.pointsBalance, grandTotalMinor + (breakdown?.loyaltyRedeemedMinor ?? 0)),
      )
    : 0

  const discountInvalid =
    discountInput !== '' && (isNaN(Number(discountInput)) || Number(discountInput) < 0)
  const locationFilled = !config.location.required || bay.trim().length > 0
  // A required attribution (barbershop's barber) blocks Charge until a staff profile is picked —
  // carwash's washer stays optional (attribution.required is false there).
  const attributionFilled = !config.attribution.required || staffProfileId != null
  const canCharge =
    selectedPackage != null && locationFilled && attributionFilled && !discountInvalid

  function selectPackage(item: CatalogItemResponse) {
    setSelectedPackage((prev) => (prev?.id === item.id ? null : item))
  }

  function toggleAddon(item: CatalogItemResponse) {
    setAddonLines((prev) => {
      const next = new Map(prev)
      if (next.has(item.id)) next.delete(item.id)
      else next.set(item.id, { item, qty: 1 })
      return next
    })
  }

  function setAddonQty(itemId: string, qty: number) {
    setAddonLines((prev) => {
      const existing = prev.get(itemId)
      if (!existing) return prev
      const next = new Map(prev)
      if (qty <= 0) next.delete(itemId)
      else next.set(itemId, { ...existing, qty })
      return next
    })
  }

  function resetTicketState() {
    setSelectedPackage(null)
    setAddonLines(new Map())
    setBay('')
    setVehiclePlate('')
    setStaffProfileId(null)
    setDiscountInput('')
    setCouponCode(null)
    setAttachedMember(null)
    setLoyaltyRedeemPoints(0)
  }

  function handlePaymentSuccess(paidTicket: TicketResponse) {
    setTicket(paidTicket)
    setLastAppliedPromotions(breakdown?.appliedPromotions ?? [])
    resetTicketState()
    setModal('receipt')
  }

  function handleNewTicket() {
    setTicket(null)
    setModal(null)
  }

  return (
    <div className="flex h-[100dvh] flex-col overflow-hidden bg-paper">
      <HeaderBar config={config} session={session} onOpenGiftCardSell={() => setShowGiftCardSell(true)} />

      <div className="relative flex min-h-0 flex-1 flex-col overflow-hidden lg:flex-row">
        {/* Catalog — packages + add-ons */}
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-5 sm:px-6">
          <section>
            <h2 className="mb-3 text-[11px] font-bold uppercase tracking-[.08em] text-ink-3">
              {t(config.primaryItemLabels.titleKey)}
            </h2>
            {packagesQuery.isLoading ? (
              <CatalogSkeleton />
            ) : packages.length === 0 ? (
              <EmptyCatalog message={t(config.primaryItemLabels.emptyKey)} />
            ) : (
              <div
                className="grid gap-3"
                style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))' }}
              >
                {packages.map((pkg) => (
                  <PackageCard
                    key={pkg.id}
                    item={pkg}
                    config={config}
                    locale={locale}
                    selected={selectedPackage?.id === pkg.id}
                    onSelect={() => selectPackage(pkg)}
                  />
                ))}
              </div>
            )}
          </section>

          <section className="mt-8">
            <h2 className="mb-3 text-[11px] font-bold uppercase tracking-[.08em] text-ink-3">
              {t(`${config.i18nNs}.addons`)}
            </h2>
            {addonsQuery.isLoading ? (
              <ChipsSkeleton />
            ) : addons.length === 0 ? (
              <EmptyCatalog message={t(`${config.i18nNs}.emptyAddons`)} />
            ) : (
              <div className="flex flex-wrap gap-2">
                {addons.map((addon) => (
                  <AddonChip
                    key={addon.id}
                    item={addon}
                    config={config}
                    locale={locale}
                    selected={addonLines.has(addon.id)}
                    onToggle={() => toggleAddon(addon)}
                  />
                ))}
              </div>
            )}
          </section>
        </div>

        {/* Summary panel */}
        <aside className="w-full shrink-0 overflow-y-auto border-t border-line bg-surface lg:w-[400px] lg:border-l lg:border-t-0">
          <SummaryPanel
            config={config}
            currency={currency}
            locale={locale}
            selectedPackage={selectedPackage}
            addonLines={addonLines}
            onRemovePackage={() => setSelectedPackage(null)}
            onSetAddonQty={setAddonQty}
            bay={bay}
            onBayChange={setBay}
            vehiclePlate={vehiclePlate}
            onVehicleChange={setVehiclePlate}
            staffProfiles={staffProfiles}
            staffProfileId={staffProfileId}
            onStaffChange={setStaffProfileId}
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
            breakdown={breakdown}
            grandTotalMinor={grandTotalMinor}
            canCharge={canCharge}
            onCharge={() => setModal('payment')}
          />
        </aside>
      </div>

      {modal === 'payment' ? (
        <ServicePaymentModal
          config={config}
          session={session}
          lines={lines}
          grandTotalMinor={grandTotalMinor}
          discountMinor={discountMinor}
          couponCode={couponCode}
          loyaltyMember={attachedMember}
          loyaltyRedeemPoints={loyaltyRedeemPoints}
          breakdown={breakdown}
          currency={currency}
          locale={locale}
          bay={bay}
          vehiclePlate={vehiclePlate}
          staffProfileId={staffProfileId}
          onSuccess={handlePaymentSuccess}
          onClose={() => setModal(null)}
        />
      ) : null}

      {showGiftCardSell ? (
        <GiftCardSellModal
          vertical={config.vertical}
          session={session}
          currency={currency}
          locale={locale}
          onClose={() => setShowGiftCardSell(false)}
        />
      ) : null}

      {modal === 'receipt' && ticket ? (
        <ServiceReceipt
          config={config}
          ticket={ticket}
          locale={locale}
          businessName={session.name}
          appliedPromotions={lastAppliedPromotions}
          onNew={handleNewTicket}
        />
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// HeaderBar — simplified terminal chrome (title + outlet picker + utilities)
// ---------------------------------------------------------------------------

function HeaderBar({
  config,
  session,
  onOpenGiftCardSell,
}: {
  config: VerticalPosConfig
  session: CompanySession
  onOpenGiftCardSell: () => void
}) {
  const { t } = useTranslation()
  const { theme, toggle } = useTheme()
  const auth = useAuth()
  const canDashboard = hasAnyRole(auth.roles, 'owner', 'manager')
  // Inline ternary (not a helper function) — mirrors VerticalComingSoon.tsx's icon selection so the
  // react-hooks/static-components lint rule can see the component reference is render-stable.
  const Icon = config.vertical === 'carwash' ? Car : config.vertical === 'barbershop' ? Scissors : Store

  return (
    <div className="flex h-16 shrink-0 items-center gap-2.5 border-b border-line bg-surface px-4 sm:px-6">
      {canDashboard ? (
        <Link
          to="/"
          aria-label={t('a11y.backToDashboard')}
          className="grid size-9 shrink-0 place-items-center rounded-xl border border-line text-ink-3 transition-all hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          <ArrowLeft className="size-4" />
        </Link>
      ) : null}

      <span className="hidden items-center gap-2 md:flex">
        <span className="grid size-8 shrink-0 place-items-center rounded-xl bg-gradient-to-br from-brand-500 to-brand-700 text-white">
          <Icon className="size-[16px]" />
        </span>
        <span className="hidden truncate font-display text-[15px] font-bold leading-tight text-ink lg:block">
          {session.name}
        </span>
      </span>

      <OutletPicker />

      <div className="flex-1" />

      <div className="flex items-center gap-1.5">
        {canDashboard ? (
          <Link
            to="/catalog"
            aria-label={t(`${config.i18nNs}.manageCatalog`)}
            title={t(`${config.i18nNs}.manageCatalog`)}
            className="grid size-10 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <Settings className="size-4" />
          </Link>
        ) : null}
        {/* Gift card sell — a distinct till action, not a cart line (ADR 0027) */}
        <button
          type="button"
          onClick={onOpenGiftCardSell}
          aria-label={t('pos.loyalty.giftCard.sellTitle')}
          title={t('pos.loyalty.giftCard.sellTitle')}
          className="grid size-10 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          <Gift className="size-4" aria-hidden="true" />
        </button>
        <button
          type="button"
          onClick={toggle}
          aria-label={t('a11y.toggleTheme')}
          className="grid size-10 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2"
        >
          {theme === 'dark' ? <Sun className="size-4" /> : <Moon className="size-4" />}
        </button>
        <button
          type="button"
          onClick={auth.logout}
          aria-label={t('nav.logout')}
          className="grid size-10 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-tint-loss hover:bg-tint-loss hover:text-loss focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          <LogOut className="size-4" />
        </button>
        <span className="grid size-10 shrink-0 place-items-center rounded-full bg-emerald-tint font-semibold text-[13px] text-emerald-2">
          {session.name.slice(0, 2).toUpperCase()}
        </span>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// PackageCard — single-select tile
// ---------------------------------------------------------------------------

function PackageCard({
  item,
  config,
  locale,
  selected,
  onSelect,
}: {
  item: CatalogItemResponse
  config: VerticalPosConfig
  locale: string
  selected: boolean
  onSelect: () => void
}) {
  const { t } = useTranslation()
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      aria-label={
        selected
          ? t(config.primaryItemLabels.selectedLabelKey, { name: item.name })
          : t(config.primaryItemLabels.selectLabelKey, { name: item.name })
      }
      className={cn(
        'flex flex-col rounded-xl border bg-surface p-4 text-left transition-all duration-200',
        selected
          ? 'border-emerald shadow-md ring-2 ring-emerald/20'
          : 'border-line shadow-sm hover:-translate-y-0.5 hover:border-emerald-line hover:shadow-md active:scale-[0.98]',
      )}
    >
      <span className="line-clamp-2 text-[13px] font-semibold leading-snug text-ink">{item.name}</span>
      {item.description ? (
        <span className="mt-1 line-clamp-2 text-xs text-ink-3">{item.description}</span>
      ) : null}
      <span className="tnum mt-2 font-mono text-[14px] font-semibold text-ink">
        {formatMoney(item.priceMinor, item.currency, locale)}
      </span>
    </button>
  )
}

// ---------------------------------------------------------------------------
// AddonChip — toggle multi-select
// ---------------------------------------------------------------------------

function AddonChip({
  item,
  config,
  locale,
  selected,
  onToggle,
}: {
  item: CatalogItemResponse
  config: VerticalPosConfig
  locale: string
  selected: boolean
  onToggle: () => void
}) {
  const { t } = useTranslation()
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-pressed={selected}
      aria-label={
        selected
          ? t(`${config.i18nNs}.addonSelectedLabel`, { name: item.name })
          : t(`${config.i18nNs}.selectAddonLabel`, { name: item.name })
      }
      className={cn(
        'flex h-10 shrink-0 items-center gap-2 rounded-full border-[1.5px] px-4 text-[13px] font-semibold transition-all focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
        selected
          ? 'border-emerald bg-emerald text-on-emerald'
          : 'border-line bg-surface text-ink-2 hover:border-emerald-line hover:bg-emerald-tint',
      )}
    >
      <span>{item.name}</span>
      <span className="tnum font-mono text-[11px] opacity-80">
        {formatMoney(item.priceMinor, item.currency, locale)}
      </span>
    </button>
  )
}

// ---------------------------------------------------------------------------
// SummaryPanel
// ---------------------------------------------------------------------------

interface SummaryPanelProps {
  config: VerticalPosConfig
  currency: string
  locale: string
  selectedPackage: CatalogItemResponse | null
  addonLines: Map<string, AddonLine>
  onRemovePackage: () => void
  onSetAddonQty: (itemId: string, qty: number) => void
  bay: string
  onBayChange: (v: string) => void
  vehiclePlate: string
  onVehicleChange: (v: string) => void
  staffProfiles: StaffProfileResponse[]
  staffProfileId: string | null
  onStaffChange: (v: string | null) => void
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
  /** Phase 4 (ADR 0027): the attached loyalty member. */
  attachedMember: MemberResponse | null
  onMemberAttach: (member: MemberResponse) => void
  onMemberClear: () => void
  loyaltyRedeemPoints: number
  maxRedeemablePoints: number
  onLoyaltyRedeemChange: (points: number) => void
  breakdown: PriceBreakdownResponse | null
  grandTotalMinor: number
  canCharge: boolean
  onCharge: () => void
}

function SummaryPanel({
  config,
  currency,
  locale,
  selectedPackage,
  addonLines,
  onRemovePackage,
  onSetAddonQty,
  bay,
  onBayChange,
  vehiclePlate,
  onVehicleChange,
  staffProfiles,
  staffProfileId,
  onStaffChange,
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
  breakdown,
  grandTotalMinor,
  canCharge,
  onCharge,
}: SummaryPanelProps) {
  const { t } = useTranslation()
  const hasSelection = !!selectedPackage || addonLines.size > 0

  return (
    <div className="flex flex-col gap-5 p-5">
      <div>
        <h2 className="mb-2 text-[11px] font-bold uppercase tracking-[.08em] text-ink-3">
          {t('servicePos.summary.title')}
        </h2>
        {!hasSelection ? (
          <p className="rounded-xl border border-dashed border-line bg-paper px-4 py-6 text-center text-sm text-ink-3">
            {t(config.primaryItemLabels.summaryEmptyKey)}
          </p>
        ) : (
          <ul className="space-y-2">
            {selectedPackage ? (
              <li className="flex items-center justify-between gap-2 rounded-xl border border-line bg-paper px-3 py-2.5">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-semibold text-ink">{selectedPackage.name}</p>
                  <p className="tnum font-mono text-xs text-ink-3">
                    {formatMoney(selectedPackage.priceMinor, currency, locale)}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={onRemovePackage}
                  aria-label={t('servicePos.summary.removeItem', { name: selectedPackage.name })}
                  className="grid size-7 shrink-0 place-items-center rounded-lg text-ink-3 hover:bg-hover hover:text-loss focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
                >
                  <X className="size-3.5" />
                </button>
              </li>
            ) : null}
            {[...addonLines.values()].map(({ item, qty }) => (
              <li
                key={item.id}
                className="flex items-center justify-between gap-2 rounded-xl border border-line bg-paper px-3 py-2.5"
              >
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-semibold text-ink">{item.name}</p>
                  <p className="tnum font-mono text-xs text-ink-3">
                    {formatMoney(item.priceMinor * qty, currency, locale)}
                  </p>
                </div>
                <div className="flex shrink-0 items-center gap-1">
                  <button
                    type="button"
                    onClick={() => onSetAddonQty(item.id, qty - 1)}
                    aria-label={t('pos.decreaseQty', { name: item.name })}
                    className="grid size-7 place-items-center rounded-lg border border-line text-ink-2 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
                  >
                    <Minus className="size-3" />
                  </button>
                  <span className="tnum w-5 text-center text-sm font-semibold text-ink">{qty}</span>
                  <button
                    type="button"
                    onClick={() => onSetAddonQty(item.id, qty + 1)}
                    aria-label={t('pos.increaseQty', { name: item.name })}
                    className="grid size-7 place-items-center rounded-lg border border-line text-ink-2 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
                  >
                    <Plus className="size-3" />
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="space-y-4">
        <Field label={t(config.location.labelKey)} htmlFor="svc-location">
          <TextInput
            id="svc-location"
            value={bay}
            onChange={(e) => onBayChange(e.target.value)}
            placeholder={t(config.location.placeholderKey)}
            required={config.location.required}
          />
        </Field>

        {config.vehicleField ? (
          <Field label={t('carwashPos.vehiclePlate')} htmlFor="svc-plate">
            <TextInput
              id="svc-plate"
              value={vehiclePlate}
              onChange={(e) => onVehicleChange(e.target.value.toUpperCase())}
              placeholder={t('carwashPos.vehiclePlatePlaceholder')}
            />
          </Field>
        ) : null}

        {config.attribution.enabled ? (
          <Field
            label={
              <span className="inline-flex items-center gap-1.5">
                {t(config.attribution.labelKey)}
                {config.attribution.required ? (
                  <Badge tone="amber" className="px-1.5 py-0 text-[10px]">
                    {t('common.required')}
                  </Badge>
                ) : null}
              </span>
            }
            htmlFor="svc-staff"
            hint={
              config.attribution.required && config.attribution.requiredHintKey
                ? t(config.attribution.requiredHintKey)
                : undefined
            }
          >
            <select
              id="svc-staff"
              className={SELECT_CLASS}
              value={staffProfileId ?? ''}
              onChange={(e) => onStaffChange(e.target.value || null)}
              required={config.attribution.required}
              aria-required={config.attribution.required}
            >
              <option value="">{t('servicePos.noneOption')}</option>
              {staffProfiles.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.displayLabel}
                </option>
              ))}
            </select>
          </Field>
        ) : null}
      </div>

      <BreakdownPanel breakdown={breakdown} grandTotalMinor={grandTotalMinor} currency={currency} locale={locale} />

      <AppliedPromotionChips promotions={appliedPromotions} currency={currency} locale={locale} />

      <CouponField
        code={couponCode}
        status={couponStatus}
        onApply={onCouponApply}
        onClear={onCouponClear}
      />

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
      />

      {showDiscountInput ? (
        <div>
          <label htmlFor="svc-discount" className="mb-1.5 block text-sm font-medium text-ink-2">
            {t('pos.addDiscount')}
          </label>
          <input
            id="svc-discount"
            type="number"
            min="0"
            step="any"
            value={discountInput}
            onChange={(e) => onDiscountChange(e.target.value)}
            placeholder="0"
            aria-describedby={discountInvalid ? 'svc-discount-error' : undefined}
            className={cn(
              'h-11 w-full rounded-xl border bg-surface px-3 text-sm text-ink placeholder:text-ink-3/50 transition-colors',
              'focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15',
              discountInvalid ? 'border-loss' : 'border-line',
            )}
          />
          {discountInvalid ? (
            <p id="svc-discount-error" className="mt-1 text-xs text-loss" role="alert">
              {t('pos.discountInvalid')}
            </p>
          ) : null}
        </div>
      ) : null}

      <Button size="xl" className="tnum w-full font-mono" disabled={!canCharge} onClick={onCharge}>
        {t('servicePos.chargeButton', { amount: formatMoney(grandTotalMinor, currency, locale) })}
      </Button>
    </div>
  )
}

// ---------------------------------------------------------------------------
// BreakdownPanel — mirrors PaymentModal's ModalBreakdown (not exported, so re-implemented here)
// ---------------------------------------------------------------------------

function BreakdownPanel({
  breakdown,
  grandTotalMinor,
  currency,
  locale,
}: {
  breakdown: PriceBreakdownResponse | null
  grandTotalMinor: number
  currency: string
  locale: string
}) {
  const { t } = useTranslation()

  if (!breakdown) {
    return (
      <div className="flex items-baseline justify-between border-t border-line pt-3 text-sm text-ink-3">
        <span>{t('pos.total')}</span>
        <span className="tnum font-mono text-xl font-medium text-ink">
          {formatMoney(grandTotalMinor, currency, locale)}
        </span>
      </div>
    )
  }

  const illustrative = breakdown.usesIllustrativeRules

  return (
    <div className="space-y-1.5 border-t border-line pt-3 text-sm">
      <div className="flex items-baseline justify-between text-ink-3">
        <span>{t('pos.subtotal')}</span>
        <span className="tnum font-mono">{formatMoney(breakdown.subtotalMinor, currency, locale)}</span>
      </div>

      {breakdown.discountMinor > 0 ? (
        <div className="flex items-baseline justify-between text-ink-3">
          <span>{t('pos.discount')}</span>
          <span className="tnum font-mono text-loss">
            − {formatMoney(breakdown.discountMinor, currency, locale)}
          </span>
        </div>
      ) : null}

      {breakdown.loyaltyRedeemedMinor > 0 ? (
        <div className="flex items-baseline justify-between text-ink-3">
          <span>{t('pos.loyalty.redeemedLabel')}</span>
          <span className="tnum font-mono text-loss">
            − {formatMoney(breakdown.loyaltyRedeemedMinor, currency, locale)}
          </span>
        </div>
      ) : null}

      <div className="flex items-center justify-between text-ink-3">
        <span className="flex items-center gap-1.5">
          {t('pos.serviceCharge')}
          {illustrative ? <EstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono">{formatMoney(breakdown.serviceChargeMinor, currency, locale)}</span>
      </div>

      <div className="flex items-center justify-between text-ink-3">
        <span className="flex items-center gap-1.5">
          {t('pos.tax')}
          {illustrative ? <EstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono">{formatMoney(breakdown.taxMinor, currency, locale)}</span>
      </div>

      <div className="flex items-baseline justify-between border-t border-line pt-1.5 mt-0.5 font-medium">
        <span className="text-ink">{t('pos.total')}</span>
        <span className="tnum font-mono text-xl text-ink">
          {formatMoney(breakdown.grandTotalMinor, currency, locale)}
        </span>
      </div>
    </div>
  )
}

function EstimatedBadge({ hint }: { hint: string }) {
  const { t } = useTranslation()
  return (
    <span title={hint} aria-label={hint}>
      <Badge tone="amber" className="text-[10px] py-0 px-1.5">
        {t('pos.estimated')}
      </Badge>
    </span>
  )
}

// ---------------------------------------------------------------------------
// Empty / loading states
// ---------------------------------------------------------------------------

function EmptyCatalog({ message }: { message: string }) {
  return (
    <p className="rounded-xl border border-dashed border-line bg-paper px-4 py-6 text-center text-sm text-ink-3">
      {message}
    </p>
  )
}

function CatalogSkeleton() {
  return (
    <div className="grid gap-3" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))' }}>
      {Array.from({ length: 4 }).map((_, i) => (
        <div key={i} className="overflow-hidden rounded-xl border border-line bg-surface p-4" aria-hidden="true">
          <div className="shimmer h-3.5 w-3/4 rounded-md" />
          <div className="shimmer mt-2 h-3 w-1/3 rounded-md" />
        </div>
      ))}
    </div>
  )
}

function ChipsSkeleton() {
  return (
    <div className="flex flex-wrap gap-2" aria-hidden="true">
      {Array.from({ length: 4 }).map((_, i) => (
        <div key={i} className="shimmer h-10 w-24 rounded-full" />
      ))}
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
      <Card className="w-full max-w-md p-10 text-center">
        <h2 className="font-display text-xl font-semibold text-ink">{t('dashboard.noCompany')}</h2>
        <p className="mt-2 text-sm text-ink-3">{t('servicePos.noCompanyHint')}</p>
        <Link
          to="/onboarding"
          className="mt-5 inline-block rounded-xl bg-emerald px-4 py-2.5 text-sm font-bold text-on-emerald shadow-sm transition-colors hover:bg-emerald-2"
        >
          {t('nav.onboarding')}
        </Link>
      </Card>
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
