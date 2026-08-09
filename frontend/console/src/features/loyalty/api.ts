/**
 * Loyalty + gift-card data plumbing (Phase 4, ADR 0027) — the console/POS client for
 * loyalty-service's member/gift-card/earn-rule surfaces, PLUS the per-vertical gift-card SELL
 * endpoints (restaurant/carwash/barbershop each mint a card at their own till).
 *
 * DTO field names are verified against services/loyalty-service's
 * member/dto/{MemberResponse,EnrollMemberRequest}.java, giftcard/dto/GiftCardResponse.java,
 * earnrule/dto/{EarnRuleResponse,EarnRuleCreateRequest}.java, AND the three verticals'
 * giftcard/dto/{SellGiftCardRequest,GiftCardSaleResponse}.java (restaurant/carwash/barbershop —
 * identical shapes, only the route path differs). See each hook's doc for the exact endpoint.
 *
 * Money rule (rule 8): every amount is an integer minor unit + ISO-4217 currency. Points are a
 * v1 currency-equivalent convention — 1 point = 1 minor unit of the sale currency (loyalty-service
 * has no notion of a currency on the member itself; the redemption value is realised in whichever
 * currency the sale is priced in) — so a points balance is rendered via formatMoney() exactly like
 * an amount, never a bare number.
 *
 * Strings rule (rule 9): no hardcoded user-facing strings here — this is data plumbing only.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch, ApiError } from '@/lib/api'
import type { CompanySession } from '@/lib/session'
import type { Vertical } from '@/features/org/api'

// ---------------------------------------------------------------------------
// Members — /api/v1/loyalty/members
// ---------------------------------------------------------------------------

/** Mirrors loyalty-service's member.dto.MemberResponse exactly. */
export interface MemberResponse {
  id: string
  displayName: string
  /** Masked to the last 4 digits, e.g. "****7890" — the full phone never leaves loyalty-service. */
  phoneTail: string
  pointsBalance: number
}

export interface EnrollMemberInput {
  phone: string
  displayName?: string | null
}

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

/**
 * On-demand exact-match lookup by phone — POST /api/v1/loyalty/members/lookup with the phone in
 * the REQUEST BODY (never a query parameter: a URL-borne phone lands in proxy access logs and
 * OTel spans — rule 6; security review W-2). 404 when unknown (see {@link isMemberNotFound}).
 * Modelled as a mutation (not a query) since it fires on a cashier action (Enter / "Look up"),
 * not automatically off a debounced input.
 */
export function useLookupMember(session: CompanySession) {
  return useMutation({
    mutationFn: (phone: string) =>
      apiFetch<MemberResponse>('/api/v1/loyalty/members/lookup', {
        method: 'POST',
        tenant: tenantOf(session),
        body: { phone },
      }),
  })
}

/** POST /api/v1/loyalty/members — enroll a new member. 409 when the phone is already enrolled. */
export function useEnrollMember(session: CompanySession) {
  return useMutation({
    mutationFn: (input: EnrollMemberInput) =>
      apiFetch<MemberResponse>('/api/v1/loyalty/members', {
        method: 'POST',
        tenant: tenantOf(session),
        body: input,
      }),
  })
}

/** Detects the 404 `member-not-found` problem+json (LoyaltyAdvice.java). */
export function isMemberNotFound(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 404 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('member-not-found')
  )
}

/** Detects the 409 `member-already-enrolled` problem+json (LoyaltyAdvice.java). */
export function isMemberAlreadyEnrolled(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 409 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('member-already-enrolled')
  )
}

// ---------------------------------------------------------------------------
// Gift cards (lookup) — /api/v1/loyalty/gift-cards/{code}
// ---------------------------------------------------------------------------

/** Mirrors loyalty-service's giftcard.dto.GiftCardResponse exactly. */
export interface GiftCardResponse {
  id: string
  code: string
  /** 'ACTIVE' | 'DEPLETED' | 'EXPIRED' | 'VOID' — only ACTIVE cards can be redeemed. */
  state: 'ACTIVE' | 'DEPLETED' | 'EXPIRED' | 'VOID'
  balanceMinor: number
  currency: string
}

/**
 * On-demand lookup by code — GET /api/v1/loyalty/gift-cards/{code}. 404 when unknown (see
 * {@link isGiftCardNotFound}). The code is case-insensitive at the till (uppercased client-side,
 * matching {@link GiftCardCodeGenerator}'s uppercase Base32 output) — modelled as a mutation for
 * the same on-demand-action reason as {@link useLookupMember}.
 */
export function useLookupGiftCard(session: CompanySession) {
  return useMutation({
    mutationFn: (code: string) =>
      apiFetch<GiftCardResponse>(`/api/v1/loyalty/gift-cards/${encodeURIComponent(code.trim().toUpperCase())}`, {
        tenant: tenantOf(session),
      }),
  })
}

/** Detects the 404 `gift-card-not-found` problem+json (LoyaltyAdvice.java). */
export function isGiftCardNotFound(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 404 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('gift-card-not-found')
  )
}

// ---------------------------------------------------------------------------
// Checkout-time redemption faults (thrown by the VERTICAL services, not loyalty-service itself —
// LoyaltyRefAdvice.java in restaurant/carwash/barbershop-service). Both are 409 Conflict.
// ---------------------------------------------------------------------------

/** A requested points redemption could not be honoured (unknown member, or balance too low — a
 * race since the quote-time preview). */
export function isLoyaltyBalanceInsufficient(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 409 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('loyalty-balance-insufficient')
  )
}

/** A requested gift-card redemption could not be honoured (unknown card, not ACTIVE, currency
 * mismatch, or balance too low — a race since the quote-time preview). */
export function isGiftCardUnusable(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 409 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('gift-card-unusable')
  )
}

// ---------------------------------------------------------------------------
// Earn rules (dashboard) — /api/v1/loyalty/earn-rules
// ---------------------------------------------------------------------------

export type EarnRuleProvenance = 'ILLUSTRATIVE_PLACEHOLDER' | 'OFFICIAL'

/** Mirrors loyalty-service's earnrule.dto.EarnRuleResponse exactly. */
export interface EarnRuleResponse {
  id: string
  ruleVersion: string
  /** Points earned per minor unit of the sale currency, in basis points. Server-generated
   * ruleVersion aside, this is the only figure an owner/manager actually configures. */
  pointsPerMinorBp: number
  minSaleMinor: number | null
  provenance: EarnRuleProvenance
  sourceNote: string
  /** 'YYYY-MM-DD' */
  effectiveFrom: string
  /** 'YYYY-MM-DD' (9999-12-31 sentinel = open-ended) */
  effectiveTo: string
  active: boolean
}

/** Mirrors loyalty-service's earnrule.dto.EarnRuleCreateRequest exactly. ruleVersion is NOT a
 * field here — the server stamps it. */
export interface EarnRuleCreateInput {
  pointsPerMinorBp: number
  minSaleMinor?: number | null
  provenance: EarnRuleProvenance
  sourceNote: string
  effectiveFrom?: string | null
  effectiveTo?: string | null
}

function earnRulesKey(session: CompanySession) {
  return ['loyalty-earn-rules', session.companyId]
}

/**
 * GET /api/v1/loyalty/earn-rules — the admin listing (activeOnly=false so an owner/manager sees
 * every historical version, newest first per the server's ordering). ADR 0049 P3b: this route is
 * DASHBOARD_ROLES-gated (unlike the member/gift-card hooks above, which are POS_ROLES and shared
 * with the till), so it uses the PERSONAL bearer — the elevation token on a device terminal;
 * identical to the single login token for a normal `user` login.
 */
export function useEarnRules(session: CompanySession) {
  return useQuery({
    queryKey: earnRulesKey(session),
    queryFn: async () => {
      const result = await apiFetch<EarnRuleResponse[]>('/api/v1/loyalty/earn-rules', {
        tenant: tenantOf(session),
        query: { activeOnly: 'false' },
        auth: 'personal',
      })
      return result ?? []
    },
  })
}

/** POST /api/v1/loyalty/earn-rules — owner/manager only (403 `earn-rule-write-forbidden`
 * otherwise); 422 `earn-rule-invalid` on an unrecognised provenance. ADR 0049 P3b: PERSONAL bearer
 * — see {@link useEarnRules}'s doc. */
export function useCreateEarnRule(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (input: EarnRuleCreateInput) =>
      apiFetch<EarnRuleResponse>('/api/v1/loyalty/earn-rules', {
        method: 'POST',
        tenant: tenantOf(session),
        auth: 'personal',
        body: input,
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: earnRulesKey(session) }),
  })
}

/** Detects the 403 `earn-rule-write-forbidden` problem+json. */
export function isEarnRuleWriteForbidden(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 403 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('earn-rule-write-forbidden')
  )
}

/** Detects the 422 `earn-rule-invalid` problem+json. */
export function isEarnRuleInvalid(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 422 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('earn-rule-invalid')
  )
}

// ---------------------------------------------------------------------------
// Gift-card SELL (mint) — a POS till action, one endpoint per vertical (config-driven like
// servicepos/config.ts). restaurant is unprefixed (grandfathered); carwash/barbershop ride their
// vertical prefix (RoutingConfig.java giftCardSalesRoute / carwash+barbershop GiftCardSaleController).
// ---------------------------------------------------------------------------

const GIFT_CARD_SELL_PATH: Record<Vertical, string> = {
  restaurant: '/api/v1/gift-card-sales',
  carwash: '/api/v1/carwash/gift-cards/sell',
  barbershop: '/api/v1/barbershop/gift-cards/sell',
}

/** Mirrors each vertical's giftcard.dto.SellGiftCardRequest exactly (identical across the three). */
export interface SellGiftCardInput {
  idempotencyKey: string
  amountMinor: number
  currency: string
  /** 'CASH' | 'QRIS' | 'CARD', or omitted for an unspecified tender. */
  tenderType?: string | null
}

/** Mirrors each vertical's giftcard.dto.GiftCardSaleResponse exactly (identical across the three). */
export interface GiftCardSaleResponse {
  giftCardSaleId: string
  giftCardId: string
  /** The printable, human-facing code — display large + offer copy (ADR 0027 till UX). */
  code: string
  businessId: string
  amountMinor: number
  currency: string
  tenderType: string | null
  occurredAt: string
}

/**
 * Sells (mints) a gift card at the till. Always captured immediately — there is no
 * pending/provider step for a gift-card sale (unlike a checkout payment), so a single call is the
 * whole flow.
 */
export function useSellGiftCard(vertical: Vertical, session: CompanySession) {
  return useMutation({
    mutationFn: (input: SellGiftCardInput) =>
      apiFetch<GiftCardSaleResponse>(GIFT_CARD_SELL_PATH[vertical], {
        method: 'POST',
        tenant: tenantOf(session),
        body: { businessId: session.businessId, ...input },
      }),
  })
}
