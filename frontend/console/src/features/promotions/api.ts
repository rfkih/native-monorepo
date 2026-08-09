/**
 * Promotions admin API — typed client for the Phase-3 promotions engine (ADR 0026): rules + coupons
 * for restaurant/carwash/barbershop. Owner/manager only (gateway DASHBOARD_ROLES on restaurant's
 * unprefixed /api/v1/promotions route, POS_ROLES + service-side owner/manager guard on the
 * vertical-prefixed carwash/barbershop routes — the console never offers this page to a cashier
 * regardless).
 *
 * DTO field names are verified against services/restaurant-service's
 * promotion/dto/{PromoRuleResponse,PromoRuleCreateRequest,PromoRulePatchRequest,CouponResponse,
 * CouponCreateRequest,CouponPatchRequest}.java — carwash/barbershop are cloned from the same
 * contract (ADR 0026 §2) and coded against here identically; only the API base path differs
 * (RoutingConfig.java: restaurant is unprefixed, carwash/barbershop ride their vertical prefix).
 *
 * Money rule (rule 8): amountMinor/minSubtotalMinor are integer minor units + an ISO-4217 currency.
 * Strings rule (rule 9): no hardcoded user-facing strings here — this is data plumbing only.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch as apiFetchBase, type RequestOptions } from '@/lib/api'
import type { CompanySession } from '@/lib/session'
import type { Vertical } from '@/features/org/api'

/**
 * ADR 0049 P3b — every call in this module is a back-office promotion/coupon admin call
 * (restaurant's `/api/v1/promotions/**` is DASHBOARD_ROLES-gated at the GATEWAY; carwash's/
 * barbershop's ride their broader `/api/v1/{carwash,barbershop}/**` POS_ROLES gateway prefix but
 * are owner/manager-checked SERVICE-SIDE — see RoutingConfig.java's `promotionsRoute` doc), so
 * every call always uses the PERSONAL bearer (the elevation token on a device terminal; identical
 * to the single login token for a normal `user` login). Shadows the shared `apiFetch` import so
 * every call site below is correct with ZERO per-call changes. (Promotions.tsx's OWN separate reads
 * of `pos/api.ts`/`servicepos/api.ts` for the item/category picker are untouched — those hit
 * POS_ROLES-open catalog routes that already work on the bare outlet bearer.)
 */
function apiFetch<T>(path: string, opts: RequestOptions = {}) {
  return apiFetchBase<T>(path, { ...opts, auth: 'personal' })
}

export type PromotionVertical = Vertical

/** Per-vertical admin API base — restaurant is unprefixed; carwash/barbershop ride their prefix. */
const API_BASE: Record<PromotionVertical, string> = {
  restaurant: '/api/v1/promotions',
  carwash: '/api/v1/carwash/promotions',
  barbershop: '/api/v1/barbershop/promotions',
}

export type PromoRuleType = 'PERCENT_OFF_ORDER' | 'AMOUNT_OFF_ORDER' | 'PERCENT_OFF_LINE'
export type PromoScopeKind = 'ITEM' | 'CATEGORY'

/** Mirrors PromoRuleResponse.java exactly (field-for-field). */
export interface PromoRuleResponse {
  id: string
  name: string
  ruleType: PromoRuleType
  scopeKind: PromoScopeKind | null
  scopeRefId: string | null
  rateBp: number | null
  amountMinor: number | null
  currency: string | null
  minSubtotalMinor: number | null
  /** Bit 0 = Monday .. bit 6 = Sunday; null = every day. */
  dowMask: number | null
  /** 'HH:mm:ss' in the rule's own tz, or null. */
  windowStart: string | null
  windowEnd: string | null
  tz: string
  priority: number
  exclusive: boolean
  requiresCoupon: boolean
  active: boolean
  /** 'YYYY-MM-DD' */
  effectiveFrom: string
  /** 'YYYY-MM-DD' (9999-12-31 sentinel = open-ended) */
  effectiveTo: string
}

/** Mirrors PromoRuleCreateRequest.java. Every field beyond name/ruleType is optional. */
export interface PromoRuleCreateInput {
  name: string
  ruleType: PromoRuleType
  scopeKind?: PromoScopeKind | null
  scopeRefId?: string | null
  rateBp?: number | null
  amountMinor?: number | null
  currency?: string | null
  minSubtotalMinor?: number | null
  dowMask?: number | null
  windowStart?: string | null
  windowEnd?: string | null
  tz?: string | null
  priority?: number | null
  exclusive?: boolean | null
  requiresCoupon?: boolean | null
  effectiveFrom?: string | null
  effectiveTo?: string | null
}

/** Mirrors PromoRulePatchRequest.java — ruleType is immutable, so it is absent here. */
export interface PromoRulePatchInput {
  name?: string | null
  scopeKind?: PromoScopeKind | null
  scopeRefId?: string | null
  rateBp?: number | null
  amountMinor?: number | null
  currency?: string | null
  minSubtotalMinor?: number | null
  dowMask?: number | null
  windowStart?: string | null
  windowEnd?: string | null
  tz?: string | null
  priority?: number | null
  exclusive?: boolean | null
  active?: boolean | null
  effectiveFrom?: string | null
  effectiveTo?: string | null
}

/** Mirrors CouponResponse.java exactly. */
export interface CouponResponse {
  id: string
  code: string
  ruleId: string
  maxRedemptions: number
  redeemedCount: number
  expiresAt: string | null
  active: boolean
}

/** Mirrors CouponCreateRequest.java. */
export interface CouponCreateInput {
  code: string
  ruleId: string
  maxRedemptions?: number | null
  expiresAt?: string | null
}

/** Mirrors CouponPatchRequest.java — code/ruleId are immutable, so absent here. */
export interface CouponPatchInput {
  maxRedemptions?: number | null
  expiresAt?: string | null
  active?: boolean | null
}

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

function rulesKey(vertical: PromotionVertical, session: CompanySession) {
  return ['promo-rules', vertical, session.companyId]
}

function couponsKey(vertical: PromotionVertical, session: CompanySession) {
  return ['promo-coupons', vertical, session.companyId]
}

// ---------------------------------------------------------------------------
// Rules
// ---------------------------------------------------------------------------

/** GET {base} — every rule for the bound tenant (activeOnly=false so the admin sees inactive ones too). */
export function usePromoRules(vertical: PromotionVertical, session: CompanySession) {
  return useQuery({
    queryKey: rulesKey(vertical, session),
    queryFn: async () => {
      const result = await apiFetch<PromoRuleResponse[]>(API_BASE[vertical], {
        tenant: tenantOf(session),
        query: { activeOnly: 'false' },
      })
      return result ?? []
    },
  })
}

export function useCreatePromoRule(vertical: PromotionVertical, session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: PromoRuleCreateInput) =>
      apiFetch<PromoRuleResponse>(API_BASE[vertical], {
        method: 'POST',
        tenant: tenantOf(session),
        body,
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: rulesKey(vertical, session) }),
  })
}

export function usePatchPromoRule(vertical: PromotionVertical, session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: PromoRulePatchInput & { id: string }) =>
      apiFetch<PromoRuleResponse>(`${API_BASE[vertical]}/${id}`, {
        method: 'PATCH',
        tenant: tenantOf(session),
        body,
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: rulesKey(vertical, session) }),
  })
}

// ---------------------------------------------------------------------------
// Coupons
// ---------------------------------------------------------------------------

export function useCoupons(vertical: PromotionVertical, session: CompanySession) {
  return useQuery({
    queryKey: couponsKey(vertical, session),
    queryFn: async () => {
      const result = await apiFetch<CouponResponse[]>(`${API_BASE[vertical]}/coupons`, {
        tenant: tenantOf(session),
        query: { activeOnly: 'false' },
      })
      return result ?? []
    },
  })
}

export function useCreateCoupon(vertical: PromotionVertical, session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: CouponCreateInput) =>
      apiFetch<CouponResponse>(`${API_BASE[vertical]}/coupons`, {
        method: 'POST',
        tenant: tenantOf(session),
        body,
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: couponsKey(vertical, session) }),
  })
}

export function usePatchCoupon(vertical: PromotionVertical, session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: CouponPatchInput & { id: string }) =>
      apiFetch<CouponResponse>(`${API_BASE[vertical]}/coupons/${id}`, {
        method: 'PATCH',
        tenant: tenantOf(session),
        body,
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: couponsKey(vertical, session) }),
  })
}
