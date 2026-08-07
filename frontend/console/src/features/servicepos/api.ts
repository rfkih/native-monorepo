/**
 * Service-POS data plumbing — carwash today, generalised for any future "package + add-ons"
 * vertical via VerticalPosConfig. Mirrors the react-query patterns in features/pos/api.ts
 * (tenant-scoped apiFetch, debounced live quote, mutation-driven cache invalidation) but is NOT a
 * copy: the wire shapes (CatalogItemResponse / StaffProfileResponse / TicketResponse) are specific
 * to the service-POS backend contract.
 *
 * Every query key is scoped by [config.vertical, ..., session.companyId, session.businessId] so
 * two verticals (or two outlets) never share a cache entry.
 */
import { useMutation, useQuery, useQueryClient, keepPreviousData, type UseQueryOptions } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { apiFetch, ApiError } from '@/lib/api'
import type { CompanySession } from '@/lib/session'
import { stashCatalog } from '@/features/pos/offline/catalogCache'
import type { EffectiveRulesResponse } from '@/features/pos/offline/provisionalPricing'
import type { CatalogItemKind, TenderType, VerticalPosConfig } from './config'

// ---------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------

/** A sellable package or add-on (GET .../packages, .../addons). */
export interface CatalogItemResponse {
  id: string
  businessId: string
  name: string
  description: string | null
  priceMinor: number
  currency: string
  active: boolean
  displayOrder: number
}

/** A staff member who can be attributed to a ticket (GET .../staff-profiles). */
export interface StaffProfileResponse {
  id: string
  businessId: string
  displayLabel: string
  /** Linked HR employee id (features/hr), or null when the profile has no employee link. */
  employeeId: string | null
  active: boolean
}

/** Mirrors backend order.dto.AppliedPromotionResponse — see features/pos/api.ts's twin for detail. */
export interface AppliedPromotionResponse {
  name: string
  type: string | null
  amountMinor: number
  lineRef: string | null
}

/**
 * Mirrors the backend PriceBreakdownResponse — identical shape to the restaurant POS's breakdown
 * (features/pos/api.ts). When usesIllustrativeRules is true the service-charge / tax lines are
 * placeholder amounts and the UI must badge them as estimated.
 *
 * Phase 3 (ADR 0026): appliedPromotions/couponStatus are populated ONLY on the quote response —
 * same contract as the restaurant POS (features/pos/api.ts's PriceBreakdownResponse doc).
 *
 * Phase 4 (ADR 0027, additive): see features/pos/api.ts's twin doc — loyaltyRedeemedMinor /
 * giftCardAppliedMinor / residualDueMinor, all defaulting to 0 pre-Phase-4.
 */
export interface PriceBreakdownResponse {
  subtotalMinor: number
  discountMinor: number
  serviceChargeMinor: number
  taxMinor: number
  grandTotalMinor: number
  currency: string
  usesIllustrativeRules: boolean
  appliedPromotions: AppliedPromotionResponse[]
  couponStatus: 'APPLIED' | 'INVALID' | 'EXHAUSTED' | null
  loyaltyRedeemedMinor: number
  giftCardAppliedMinor: number
  residualDueMinor: number
}

/** One quote/checkout line — a package (qty always 1) or an add-on (qty >= 1). */
export interface TicketLineInput {
  itemType: CatalogItemKind
  itemId: string
  qty: number
}

/** A priced line as returned on a TicketResponse. */
export interface TicketLineResponse {
  itemType: CatalogItemKind
  itemId: string
  name: string
  /** Unit price in minor units — multiply by qty for the line total (no separate total field). */
  priceMinor: number
  currency: string
  qty: number
}

/** Mirrors the restaurant POS's PaymentResponse, nested under a ticket instead of an order. */
export interface TicketPaymentResponse {
  paymentId: string
  ticketId: string
  tenderType: TenderType
  /** String name of Payment.Status enum: PENDING | CAPTURED | VOIDED | REFUNDED | ... */
  status: string
  amountMinor: number
  currency: string
  /** Cash only — null for digital tenders. */
  tenderedMinor: number | null
  /** Cash only — null for digital tenders. */
  changeMinor: number | null
  /** True for QRIS/CARD until a real provider adapter lands (ADR 0006). */
  providerPending: boolean
  /** null until the payment is CAPTURED. */
  saleId: string | null
}

export interface TicketResponse {
  ticketId: string
  businessId: string
  /** Carwash's location field — absent on barbershop responses (which carry `chair`). */
  bay?: string | null
  /** Barbershop's location field — absent on carwash responses (which carry `bay`). */
  chair?: string | null
  /** Carwash only — barbershop tickets carry no vehicle plate at all. */
  vehiclePlate?: string | null
  staffProfileId: string | null
  staffLabel: string | null
  saleId: string | null
  breakdown: PriceBreakdownResponse
  occurredAt: string
  lines: TicketLineResponse[]
  payment: TicketPaymentResponse | null
}

/** The ticket's location value under whichever wire field this vertical uses (bay/chair). */
export function ticketLocationOf(config: VerticalPosConfig, ticket: TicketResponse): string | null {
  return (config.location.fieldName === 'chair' ? ticket.chair : ticket.bay) ?? null
}

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

// ---------------------------------------------------------------------------
// Error helpers — the outlet-not-assigned check mirrors features/pos; module-not-entitled is new
// to service-POS and lives here (local, per rule: never edit lib/api.ts for a feature-specific check).
// ---------------------------------------------------------------------------

/**
 * Detects a 403 module-not-entitled problem+json (the company/outlet is not entitled to this
 * vertical's module). Surfaces on checkout the same way outlet-not-assigned does.
 */
export function isModuleNotEntitled(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 403 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('module-not-entitled')
  )
}

// ---------------------------------------------------------------------------
// Catalog reads — packages / add-ons / staff profiles
// ---------------------------------------------------------------------------

/**
 * Read options: the POS terminal reads active-only (default); CatalogManagement reads EVERYTHING
 * (includeInactive) so a deactivated item stays visible and can be reactivated.
 */
export interface CatalogReadOptions {
  includeInactive?: boolean
}

export function useCatalogPackages(
  config: VerticalPosConfig,
  session: CompanySession,
  opts: CatalogReadOptions = {},
) {
  const activeOnly = opts.includeInactive ? 'false' : 'true'
  return useQuery({
    queryKey: [
      'servicepos',
      config.vertical,
      'packages',
      session.companyId,
      session.businessId,
      activeOnly,
    ],
    queryFn: async () => {
      const result = await apiFetch<CatalogItemResponse[]>(
        `${config.apiBase}/${config.packagesPath}`,
        {
          tenant: tenantOf(session),
          // The catalog is OUTLET-scoped (business_id on every row): filter server-side to the
          // effective outlet so a multi-outlet company's terminals only see their own offerings.
          query: { activeOnly, businessId: session.businessId },
        },
      )
      const rows = result ?? []
      // Write-through offline cache (Phase 5, ADR 0028) — active-only reads only (the terminal's
      // own read shape), best-effort.
      if (activeOnly === 'true') void stashCatalog(session.companyId, config.vertical, 'packages', rows)
      return rows
    },
  })
}

export function useCatalogAddons(
  config: VerticalPosConfig,
  session: CompanySession,
  opts: CatalogReadOptions = {},
) {
  const activeOnly = opts.includeInactive ? 'false' : 'true'
  return useQuery({
    queryKey: [
      'servicepos',
      config.vertical,
      'addons',
      session.companyId,
      session.businessId,
      activeOnly,
    ],
    queryFn: async () => {
      const result = await apiFetch<CatalogItemResponse[]>(`${config.apiBase}/addons`, {
        tenant: tenantOf(session),
        query: { activeOnly, businessId: session.businessId },
      })
      const rows = result ?? []
      if (activeOnly === 'true') void stashCatalog(session.companyId, config.vertical, 'addons', rows)
      return rows
    },
  })
}

export function useStaffProfiles(
  config: VerticalPosConfig,
  session: CompanySession,
  opts: CatalogReadOptions = {},
) {
  const activeOnly = opts.includeInactive ? 'false' : 'true'
  return useQuery({
    queryKey: [
      'servicepos',
      config.vertical,
      'staff-profiles',
      session.companyId,
      session.businessId,
      activeOnly,
    ],
    queryFn: async () => {
      const result = await apiFetch<StaffProfileResponse[]>(`${config.apiBase}/staff-profiles`, {
        tenant: tenantOf(session),
        query: { activeOnly, businessId: session.businessId },
      })
      const rows = result ?? []
      if (activeOnly === 'true') {
        void stashCatalog(session.companyId, config.vertical, 'staffProfiles', rows)
      }
      return rows
    },
  })
}

// ---------------------------------------------------------------------------
// Effective pricing rules — GET {apiBase}/pricing/effective-rules?businessId=
// Phase 5 (ADR 0028): stashed into the offline catalog cache — see features/pos/api.ts's twin.
// ---------------------------------------------------------------------------

export function useEffectiveRules(config: VerticalPosConfig, session: CompanySession) {
  return useQuery({
    queryKey: ['servicepos', config.vertical, 'pricing-effective-rules', session.companyId, session.businessId],
    queryFn: async () => {
      const raw = await apiFetch<EffectiveRulesResponse>(`${config.apiBase}/pricing/effective-rules`, {
        tenant: tenantOf(session),
        query: { businessId: session.businessId },
      })
      if (!raw) return raw
      // The backend's `currency` is nullable (no rule seeded for this business at all) — the
      // company's base currency is always the correct fallback (rule 8: every amount needs one).
      const result: EffectiveRulesResponse = { ...raw, currency: raw.currency ?? session.baseCurrency }
      void stashCatalog(session.companyId, config.vertical, 'effectiveRules', result)
      return result
    },
    staleTime: 5 * 60_000,
  })
}

// ---------------------------------------------------------------------------
// Live quote — debounced, no side-effects (mirrors features/pos useQuote exactly).
// ---------------------------------------------------------------------------

/**
 * Phase 4 (ADR 0027): loyaltyMemberId/loyaltyRedeemPoints (optional) preview a points redemption —
 * see features/pos/api.ts's useQuote twin doc for the never-throws-on-preview contract and why
 * giftCardId/giftCardRedeemMinor are deliberately NOT threaded here (the payment modal computes
 * residualDueMinor client-side instead — components/GiftCardField.tsx).
 */
export function useTicketQuote(
  config: VerticalPosConfig,
  session: CompanySession,
  lines: TicketLineInput[],
  discountMinor: number,
  couponCode: string | null = null,
  loyaltyMemberId: string | null = null,
  loyaltyRedeemPoints: number = 0,
  /** Phase 5 (ADR 0028): the caller passes `!offline` — see features/pos/api.ts's useQuote twin. */
  enabledOverride: boolean = true,
) {
  const [debounced, setDebounced] = useState<{
    lines: TicketLineInput[]
    discountMinor: number
    couponCode: string | null
    loyaltyMemberId: string | null
    loyaltyRedeemPoints: number
  }>(() => ({ lines, discountMinor, couponCode, loyaltyMemberId, loyaltyRedeemPoints }))
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => {
      setDebounced({ lines, discountMinor, couponCode, loyaltyMemberId, loyaltyRedeemPoints })
    }, 400)
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current)
    }
  }, [lines, discountMinor, couponCode, loyaltyMemberId, loyaltyRedeemPoints])

  const enabled = debounced.lines.length > 0 && enabledOverride

  return useQuery({
    queryKey: [
      'servicepos',
      config.vertical,
      'quote',
      session.companyId,
      session.businessId,
      debounced.lines,
      debounced.discountMinor,
      debounced.couponCode,
      debounced.loyaltyMemberId,
      debounced.loyaltyRedeemPoints,
    ],
    enabled,
    placeholderData: keepPreviousData,
    staleTime: 0,
    queryFn: () =>
      apiFetch<PriceBreakdownResponse>(`${config.apiBase}/tickets/quote`, {
        method: 'POST',
        tenant: tenantOf(session),
        body: {
          businessId: session.businessId,
          lines: debounced.lines,
          discountMinor: debounced.discountMinor > 0 ? debounced.discountMinor : null,
          couponCode: debounced.couponCode || null,
          loyaltyMemberId: debounced.loyaltyMemberId || null,
          loyaltyRedeemPoints: debounced.loyaltyRedeemPoints > 0 ? debounced.loyaltyRedeemPoints : null,
        },
      }),
  })
}

// ---------------------------------------------------------------------------
// Checkout / capture / read-one
// ---------------------------------------------------------------------------

export interface TicketCheckoutInput {
  /**
   * Minted ONCE when the payment attempt begins (modal mount) and REUSED across retries of the
   * same attempt — never per click. If the first attempt commits server-side but the response is
   * lost, the retry replays the same key and resolves to the SAME ticket instead of charging
   * twice (the BillDetail freshIdempotencyKey pattern; review W1).
   */
  idempotencyKey: string
  /** The location value; serialized under this vertical's wire field name (bay/chair). */
  bay: string
  vehiclePlate?: string | null
  staffProfileId?: string | null
  discountMinor?: number
  lines: TicketLineInput[]
  payment: { tenderType: TenderType; tenderedMinor?: number }
  /** Phase 3 (ADR 0026): optional coupon code. Checkout REJECTS a bad/exhausted code (unlike the quote). */
  couponCode?: string | null
  /** Phase 4 (ADR 0027): see features/pos/api.ts's CheckoutInput twin fields. */
  loyaltyMemberId?: string | null
  loyaltyRedeemPoints?: number | null
  giftCardId?: string | null
  giftCardRedeemMinor?: number | null
}

export function useTicketCheckout(config: VerticalPosConfig, session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      idempotencyKey,
      bay,
      vehiclePlate,
      staffProfileId,
      discountMinor,
      lines,
      payment,
      couponCode,
      loyaltyMemberId,
      loyaltyRedeemPoints,
      giftCardId,
      giftCardRedeemMinor,
    }: TicketCheckoutInput) =>
      apiFetch<TicketResponse>(`${config.apiBase}/tickets/checkout`, {
        method: 'POST',
        tenant: tenantOf(session),
        body: {
          businessId: session.businessId,
          idempotencyKey,
          // The location rides this vertical's wire field name (carwash `bay` is @NotBlank;
          // barbershop `chair` is optional → null when the input is blank). The vehicle plate is
          // a carwash-only field — omitted entirely for verticals whose contract lacks it.
          [config.location.fieldName]: bay.trim() ? bay : null,
          ...(config.vehicleField ? { vehiclePlate: vehiclePlate || null } : {}),
          staffProfileId: staffProfileId || null,
          discountMinor: discountMinor && discountMinor > 0 ? discountMinor : null,
          lines,
          payment,
          couponCode: couponCode || null,
          loyaltyMemberId: loyaltyMemberId || null,
          loyaltyRedeemPoints: loyaltyRedeemPoints && loyaltyRedeemPoints > 0 ? loyaltyRedeemPoints : null,
          giftCardId: giftCardId || null,
          giftCardRedeemMinor: giftCardRedeemMinor && giftCardRedeemMinor > 0 ? giftCardRedeemMinor : null,
        },
      }),
    onSuccess: (res) => {
      // A CAPTURED payment recorded a Sale → consolidated dashboard revenue changes.
      if (res?.payment?.status === 'CAPTURED') {
        void qc.invalidateQueries({ queryKey: ['pnl'] })
      }
    },
  })
}

/** The "Mark as paid" step for QRIS/CARD — captures a PENDING ticket payment. */
export function useTicketCapture(config: VerticalPosConfig, session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (ticketId: string) =>
      apiFetch<TicketResponse>(`${config.apiBase}/tickets/${ticketId}/capture`, {
        method: 'POST',
        tenant: tenantOf(session),
      }),
    onSuccess: (res) => {
      if (res?.payment?.status === 'CAPTURED') {
        void qc.invalidateQueries({ queryKey: ['pnl'] })
      }
    },
  })
}

/**
 * `options.refetchInterval` (ADR 0045): GATEWAY QRIS re-uses this SAME ticket read as its capture
 * poll — see `features/pos/api.ts`'s `useReceipt` twin doc. `ticket.payment?.status` is what the
 * caller watches for CAPTURED.
 */
export function useTicket(
  config: VerticalPosConfig,
  session: CompanySession,
  ticketId: string | null,
  options: Pick<UseQueryOptions<TicketResponse | null>, 'refetchInterval'> = {},
) {
  return useQuery({
    queryKey: ['servicepos', config.vertical, 'ticket', session.companyId, ticketId],
    enabled: ticketId != null,
    refetchInterval: options.refetchInterval,
    queryFn: () =>
      apiFetch<TicketResponse>(`${config.apiBase}/tickets/${ticketId}`, {
        tenant: tenantOf(session),
      }),
  })
}

// ---------------------------------------------------------------------------
// Catalog management mutations (owner/manager back office — CatalogManagement.tsx)
// ---------------------------------------------------------------------------

function catalogKey(config: VerticalPosConfig, session: CompanySession, kind: string) {
  return ['servicepos', config.vertical, kind, session.companyId, session.businessId]
}

export interface CreateCatalogItemInput {
  businessId: string
  name: string
  description?: string | null
  priceMinor: number
  currency: string
}

export interface UpdateCatalogItemInput {
  id: string
  name?: string
  description?: string | null
  priceMinor?: number
  active?: boolean
  displayOrder?: number
}

export function useCreatePackage(config: VerticalPosConfig, session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateCatalogItemInput) =>
      apiFetch<CatalogItemResponse>(`${config.apiBase}/${config.packagesPath}`, {
        method: 'POST',
        tenant: tenantOf(session),
        body,
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: catalogKey(config, session, 'packages') }),
  })
}

export function useUpdatePackage(config: VerticalPosConfig, session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: UpdateCatalogItemInput) =>
      apiFetch<CatalogItemResponse>(`${config.apiBase}/${config.packagesPath}/${id}`, {
        method: 'PATCH',
        tenant: tenantOf(session),
        body,
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: catalogKey(config, session, 'packages') }),
  })
}

export function useCreateAddon(config: VerticalPosConfig, session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateCatalogItemInput) =>
      apiFetch<CatalogItemResponse>(`${config.apiBase}/addons`, {
        method: 'POST',
        tenant: tenantOf(session),
        body,
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: catalogKey(config, session, 'addons') }),
  })
}

export function useUpdateAddon(config: VerticalPosConfig, session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: UpdateCatalogItemInput) =>
      apiFetch<CatalogItemResponse>(`${config.apiBase}/addons/${id}`, {
        method: 'PATCH',
        tenant: tenantOf(session),
        body,
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: catalogKey(config, session, 'addons') }),
  })
}

export interface CreateStaffProfileInput {
  businessId: string
  displayLabel: string
  employeeId?: string | null
}

export interface UpdateStaffProfileInput {
  id: string
  displayLabel?: string
  employeeId?: string | null
  active?: boolean
}

export function useCreateStaffProfile(config: VerticalPosConfig, session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateStaffProfileInput) =>
      apiFetch<StaffProfileResponse>(`${config.apiBase}/staff-profiles`, {
        method: 'POST',
        tenant: tenantOf(session),
        body,
      }),
    onSuccess: () =>
      void qc.invalidateQueries({ queryKey: catalogKey(config, session, 'staff-profiles') }),
  })
}

export function useUpdateStaffProfile(config: VerticalPosConfig, session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: UpdateStaffProfileInput) =>
      apiFetch<StaffProfileResponse>(`${config.apiBase}/staff-profiles/${id}`, {
        method: 'PATCH',
        tenant: tenantOf(session),
        body,
      }),
    onSuccess: () =>
      void qc.invalidateQueries({ queryKey: catalogKey(config, session, 'staff-profiles') }),
  })
}
