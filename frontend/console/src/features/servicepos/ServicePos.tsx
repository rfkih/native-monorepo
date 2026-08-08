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
import { Gift, LogOut, Moon, Settings, Sun, UserRound } from 'lucide-react'
import { useSession, type CompanySession } from '@/lib/session'
import { useAuth, hasAnyRole } from '@/lib/authContext'
import { useTheme } from '@/lib/theme'
import { OutletPicker } from '@/components/OutletPicker'
import { localeOf } from '@/i18n'
import { OutletGate } from '@/components/OutletGate'
import { GiftCardSellModal } from '@/components/GiftCardSellModal'
import type { MemberResponse } from '@/features/loyalty/api'
import { useOffline } from '@/features/pos/offline/useOffline'
import { useCachedCatalogFallback } from '@/features/pos/offline/catalogCache'
import { computeProvisionalPricing, toDisplayBreakdown } from '@/features/pos/offline/provisionalPricing'
import type { EffectiveRulesResponse } from '@/features/pos/offline/provisionalPricing'
import { parseDiscountInput } from '@/features/pos/lib/discountInput'
import { SyncCenter } from '@/features/pos/offline/SyncCenter'
import type { SaleQueueRow } from '@/features/pos/offline/db'
import type { VerticalPosConfig } from './config'
import {
  useCatalogPackages,
  useCatalogAddons,
  useStaffProfiles,
  useEffectiveRules,
  useTicketQuote,
  type AppliedPromotionResponse,
  type CatalogItemResponse,
  type PriceBreakdownResponse,
  type StaffProfileResponse,
  type TicketLineInput,
  type TicketLineResponse,
  type TicketResponse,
} from './api'
import { ServicePaymentModal } from './ServicePaymentModal'
import { ServiceReceipt } from './ServiceReceipt'
import { PosStatusBar } from '@/features/pos-shell/layout/PosStatusBar'
import { TillMenuSheet } from '@/features/pos-shell/layout/TillMenuSheet'
import { usePrinterStatusAction } from '@/features/pos-shell/layout/usePrinterStatusAction'
import { PackageCard } from './components/PackageCard'
import { AddonChip } from './components/AddonChip'
import { SummaryPanel, type AddonLine } from './components/SummaryPanel'
import { EmptyCatalog, CatalogSkeleton, ChipsSkeleton, NoCompany } from './components/ServiceStates'


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


function ServicePosInner({ config, session }: { config: VerticalPosConfig; session: CompanySession }) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const auth = useAuth()
  // The manual discount is owner/manager-only (ADR 0026 §5; the server 403s anyway — this hides the
  // input for a cashier so it never sees an affordance it cannot use).
  const canManualDiscount = hasAnyRole(auth.roles, 'owner', 'manager')
  const canDashboard = hasAnyRole(auth.roles, 'owner', 'manager')
  const { theme, toggle } = useTheme()

  const packagesQuery = useCatalogPackages(config, session)
  const addonsQuery = useCatalogAddons(config, session)
  const staffQuery = useStaffProfiles(config, session)
  const effectiveRulesQuery = useEffectiveRules(config, session)

  // Phase 5 offline mode (ADR 0028) — see features/pos/Pos.tsx's twin doc for the fallback strategy.
  const { offline, queuedCount, rejectedCount } = useOffline()
  // Always-visible printer status (P1 printing-flow hardening) — see the hook's own doc.
  const printerStatusAction = usePrinterStatusAction()
  const [showSyncCenter, setShowSyncCenter] = useState(false)
  const [showTillMenu, setShowTillMenu] = useState(false)
  const cachedPackages = useCachedCatalogFallback<CatalogItemResponse[]>(
    session.companyId,
    config.vertical,
    'packages',
    offline && !packagesQuery.data,
  )
  const cachedAddons = useCachedCatalogFallback<CatalogItemResponse[]>(
    session.companyId,
    config.vertical,
    'addons',
    offline && !addonsQuery.data,
  )
  const cachedStaffProfiles = useCachedCatalogFallback<StaffProfileResponse[]>(
    session.companyId,
    config.vertical,
    'staffProfiles',
    offline && !staffQuery.data,
  )
  const cachedEffectiveRules = useCachedCatalogFallback<EffectiveRulesResponse>(
    session.companyId,
    config.vertical,
    'effectiveRules',
    offline && !effectiveRulesQuery.data,
  )
  const effectiveRules = effectiveRulesQuery.data ?? cachedEffectiveRules ?? null

  const packages = (packagesQuery.data ?? cachedPackages ?? []).filter((p) => p.active)
  const addons = (addonsQuery.data ?? cachedAddons ?? []).filter((a) => a.active)
  const staffProfiles = (staffQuery.data ?? cachedStaffProfiles ?? []).filter((s) => s.active)

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
  // Phase 5 (ADR 0028): true when `ticket` was built client-side from an enqueued offline sale.
  const [placedProvisional, setPlacedProvisional] = useState(false)
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
  const clientSubtotalMinor =
    (selectedPackage?.priceMinor ?? 0) +
    [...addonLines.values()].reduce((sum, l) => sum + l.item.priceMinor * l.qty, 0)
  // Offline (Phase 5, ADR 0028) — see features/pos/Pos.tsx's twin doc for the provisional-breakdown
  // strategy; the live quote is disabled (it can only fail) via `!offline`.
  const quoteQuery = useTicketQuote(
    config,
    session,
    lines,
    discountMinor,
    couponCode,
    attachedMember?.id ?? null,
    loyaltyRedeemPoints,
    !offline,
  )
  const provisionalBreakdown =
    offline && effectiveRules && lines.length > 0
      ? computeProvisionalPricing(clientSubtotalMinor, effectiveRules, {
          fixedDiscountMinor: discountMinor > 0 ? discountMinor : null,
})
      : null
  const breakdown: PriceBreakdownResponse | null = offline
    ? provisionalBreakdown
      ? toDisplayBreakdown(provisionalBreakdown)
      : null
    : (quoteQuery.data ?? null)

  const grandTotalMinor = breakdown?.grandTotalMinor ?? Math.max(0, clientSubtotalMinor - discountMinor)
  // The redemption ceiling — see features/pos/Pos.tsx's twin computation doc. Offline: points
  // redemption is disabled entirely (server 422s it on an offline replay) — ceiling forced to 0.
  const maxRedeemablePoints = !offline && attachedMember
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
    setPlacedProvisional(false)
    setLastAppliedPromotions(breakdown?.appliedPromotions ?? [])
    resetTicketState()
    setModal('receipt')
  }

  /** Phase 5 (ADR 0028): the sale was durably enqueued (never POSTed) — build a client-side ticket
   * from the current selection + provisional breakdown. See features/pos/Pos.tsx's twin doc. */
  function handleOfflineSuccess(row: SaleQueueRow, tenderedMinor: number, changeMinor: number) {
    const linesOut: TicketLineResponse[] = []
    if (selectedPackage) {
      linesOut.push({
        itemType: config.primaryItemType,
        itemId: selectedPackage.id,
        name: selectedPackage.name,
        priceMinor: selectedPackage.priceMinor,
        currency: selectedPackage.currency,
        qty: 1,
})
    }
    for (const { item, qty } of addonLines.values()) {
      linesOut.push({
        itemType: 'ADDON',
        itemId: item.id,
        name: item.name,
        priceMinor: item.priceMinor,
        currency: item.currency,
        qty,
})
    }
    const staffLabel = staffProfileId
      ? (staffProfiles.find((s) => s.id === staffProfileId)?.displayLabel ?? null)
      : null
    const locationValue = bay.trim() ? bay : null
    const ticketOut: TicketResponse = {
      ticketId: row.idempotencyKey,
      businessId: session.businessId,
      bay: config.location.fieldName === 'bay' ? locationValue : undefined,
      chair: config.location.fieldName === 'chair' ? locationValue : undefined,
      vehiclePlate: config.vehicleField ? vehiclePlate || null : undefined,
      staffProfileId,
      staffLabel,
      saleId: null,
      breakdown: toDisplayBreakdown(row.provisional),
      occurredAt: new Date().toISOString(),
      lines: linesOut,
      payment: {
        paymentId: row.idempotencyKey,
        ticketId: row.idempotencyKey,
        tenderType: 'CASH',
        status: 'CAPTURED',
        amountMinor: row.provisional.grandTotalMinor,
        currency: row.provisional.currency,
        tenderedMinor,
        changeMinor,
        providerPending: false,
        saleId: null,
},
}
    setTicket(ticketOut)
    setPlacedProvisional(true)
    setLastAppliedPromotions([])
    resetTicketState()
    setModal('receipt')
  }

  function handleNewTicket() {
    setTicket(null)
    setPlacedProvisional(false)
    setModal(null)
  }

  return (
    <div className="flex h-[100dvh] flex-col overflow-hidden bg-paper">
      {/* P5: the shared terminal chrome — same band as the restaurant POS. */}
      <PosStatusBar
        businessName={session.name}
        outletPicker={<OutletPicker />}
        showBack={canDashboard}
        offline={offline}
        queuedCount={queuedCount + rejectedCount}
        onConnectionClick={() => setShowSyncCenter(true)}
        pinned={[printerStatusAction]}
        onOverflowClick={() => setShowTillMenu((v) => !v)}
        overflowOpen={showTillMenu}
      />

      {showTillMenu ? (
        <TillMenuSheet
          onClose={() => setShowTillMenu(false)}
          items={[
            {
              key: 'giftcard',
              icon: <Gift className="size-4" aria-hidden="true" />,
              label: t('pos.loyalty.giftCard.sellTitle'),
              onSelect: () => setShowGiftCardSell(true),
              disabled: offline,
              disabledTitle: t('offline.disabled.giftCard'),
            },
            ...(canDashboard
              ? [
                  {
                    key: 'catalog',
                    icon: <Settings className="size-4" aria-hidden="true" />,
                    label: t(`${config.i18nNs}.manageCatalog`),
                    to: '/catalog',
                  },
                ]
              : []),
            // The door to the employee self-service surface — /me is the always-available floor,
            // so a cashier who is also an employee can always reach their payslips/time-off/claims
            // from the till (on phone /me carries the employee tab bar).
            { key: 'me', icon: <UserRound className="size-4" aria-hidden="true" />, label: t('me.tillMenuLabel'), to: '/me' },
            {
              key: 'theme',
              icon: theme === 'dark' ? <Sun className="size-4" aria-hidden="true" /> : <Moon className="size-4" aria-hidden="true" />,
              label: t('a11y.toggleTheme'),
              onSelect: toggle,
            },
            {
              key: 'logout',
              icon: <LogOut className="size-4" aria-hidden="true" />,
              label: t('nav.logout'),
              onSelect: auth.logout,
              danger: true,
            },
          ]}
        />
      ) : null}

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
        <aside className="max-h-[45dvh] w-full shrink-0 overflow-y-auto border-t border-line bg-surface lg:max-h-none lg:w-[400px] lg:border-l lg:border-t-0">
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
          offline={offline}
          onOfflineSuccess={handleOfflineSuccess}
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
          provisional={placedProvisional}
          onNew={handleNewTicket}
        />
      ) : null}

      {showSyncCenter ? <SyncCenter locale={locale} onClose={() => setShowSyncCenter(false)} /> : null}
    </div>
  )
}
// (parseDiscountInput moved to features/pos/lib/discountInput.ts — redesign P1.)
