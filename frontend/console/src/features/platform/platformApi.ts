/**
 * Platform settlements API — typed client for finance-service's per-channel payout settlement
 * (ADR 0036 Phase C2): what a delivery/marketplace channel (GoFood/GrabFood-style — see
 * features/channels/) still owes, and recording a payout against it. Dashboard-facing
 * (owner/manager only, gateway DASHBOARD_ROLES) — mirrors features/channels/channelsApi.ts's shape
 * exactly: a `session: CompanySession` positional arg, keyed by companyId only (company-wide, not
 * outlet-scoped).
 *
 * Money rule 8: every amount on the wire is an integer MINOR unit + an ISO-4217 currency, never a
 * float. `outstandingMinor` may be NEGATIVE (a refund clawback landing after the channel was
 * settled in full) — the caller renders that in the loss color, never clamped to zero (ADR 0036 §
 * "negative balances are tolerated by design").
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch as apiFetchBase, type RequestOptions } from '@/lib/api'
import type { CompanySession } from '@/lib/session'

/**
 * ADR 0049 P3b — every call in this module targets a DASHBOARD_ROLES-gated back-office route
 * (`/api/v1/platform-settlements/**`), so it always uses the PERSONAL bearer (the elevation token
 * on a device terminal; identical to the single login token for a normal `user` login). Shadows the
 * shared `apiFetch` import so every call site below is correct with ZERO per-call changes.
 */
function apiFetch<T>(path: string, opts: RequestOptions = {}) {
  return apiFetchBase<T>(path, { ...opts, auth: 'personal' })
}

/** Mirrors backend PlatformOutstandingResponse exactly. */
export interface PlatformOutstanding {
  channelCode: string
  currency: string
  outstandingMinor: number
}

/** Mirrors backend PlatformSettlementResponse exactly. `feeMinor` is server-derived (gross − net). */
export interface PlatformSettlementRecord {
  id: string
  channelCode: string
  grossMinor: number
  netMinor: number
  feeMinor: number
  currency: string
  settledAt: string
  /** True once this payout has been taken back — the row stays in history, but it is not live. */
  voided: boolean
}

/** POST /api/v1/platform-settlements body. */
export interface SettlePlatformBody {
  channelCode: string
  grossMinor: number
  netMinor: number
  currency: string
}

/**
 * Mutation variables for {@link useSettlePlatform}. `idempotencyKey` must be minted ONCE per
 * user-confirmed attempt (see ConfirmSettleDialog — `crypto.randomUUID()` captured when the
 * confirm dialog opens) and passed in here, NOT generated inside `mutationFn` (which re-runs on
 * every TanStack Query retry and would mint a fresh key per retry, defeating the backend's
 * replay-by-key de-dupe) — the RecordPaymentVariables idiom (features/ap/api.ts).
 */
export interface SettlePlatformVariables extends SettlePlatformBody {
  idempotencyKey: string
}

/** Mirrors backend PayoutSourceResponse — what ONE PAYER still owes, and the sources behind it. */
export interface PayoutSource {
  sourceCode: string
  currency: string
  outstandingMinor: number
  lines: PayoutSourceLine[]
}

/** One sub-ledger row inside a payer's balance. `settleable` is false for card (no fee account). */
export interface PayoutSourceLine {
  sourceKind: 'MARKETPLACE' | 'QRIS' | 'CARD'
  channelCode: string
  outstandingMinor: number
  settleable: boolean
}

/** Mirrors backend OverdueSourceResponse. `daysSinceLastPayout` is null when it never paid out. */
export interface OverdueSource {
  sourceCode: string
  currency: string
  outstandingMinor: number
  daysSinceLastPayout: number | null
  cadenceDays: number
}

/** Mirrors backend SettlementSourceResponse — who the merchant says pays a tender family. */
export interface SettlementSourceConfig {
  sourceKind: 'QRIS' | 'CARD'
  sourceCode: string
}

/** POST /api/v1/platform-settlements/payouts body — the net is entered ONCE, the fee is derived. */
export interface SettlePayoutBody {
  sourceCode: string
  lines: { sourceKind: string; channelCode: string; grossMinor: number }[]
  netMinor: number
  currency: string
}

/** See {@link SettlePlatformVariables} for why the key is minted by the caller, not the mutation. */
export interface SettlePayoutVariables extends SettlePayoutBody {
  idempotencyKey: string
}

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

function outstandingKey(session: CompanySession) {
  return ['platform-settlements-outstanding', session.companyId]
}

/** Base key for the history cache — a bare channelCode-less invalidate matches every filtered
 *  variant too (TanStack Query's partial-key match, the ap/api.ts invalidateBill idiom). */
function historyBaseKey(session: CompanySession) {
  return ['platform-settlements-history', session.companyId]
}

function historyKey(session: CompanySession, channelCode: string | undefined) {
  return [...historyBaseKey(session), channelCode ?? '']
}

function sourcesKey(session: CompanySession) {
  return ['platform-payout-sources', session.companyId]
}

function overdueKey(session: CompanySession) {
  return ['platform-payout-overdue', session.companyId]
}

function sourceConfigKey(session: CompanySession) {
  return ['settlement-source-config', session.companyId]
}

/**
 * GET /api/v1/platform-settlements/sources — what each PAYER still owes, grouped the way the money
 * actually arrives: one Shopee transfer covers both its ShopeeFood orders and its counter QRIS.
 */
export function usePayoutSources(session: CompanySession) {
  return useQuery({
    queryKey: sourcesKey(session),
    queryFn: async () => {
      const result = await apiFetch<PayoutSource[]>('/api/v1/platform-settlements/sources', {
        tenant: tenantOf(session),
      })
      return result ?? []
    },
  })
}

/**
 * GET /api/v1/platform-settlements/overdue — payers whose money has sat past its usual payout
 * cycle. Empty is the normal answer; the Beranda card renders nothing at all when it is.
 */
export function useOverdueSources(session: CompanySession, enabled = true) {
  return useQuery({
    enabled,
    queryKey: overdueKey(session),
    queryFn: async () => {
      const result = await apiFetch<OverdueSource[]>('/api/v1/platform-settlements/overdue', {
        tenant: tenantOf(session),
      })
      return result ?? []
    },
  })
}

/** GET /api/v1/settlement-sources — the payers the merchant has named per tender family. */
export function useSettlementSources(session: CompanySession) {
  return useQuery({
    queryKey: sourceConfigKey(session),
    queryFn: async () => {
      const result = await apiFetch<SettlementSourceConfig[]>('/api/v1/settlement-sources', {
        tenant: tenantOf(session),
      })
      return result ?? []
    },
  })
}

/**
 * PUT /api/v1/settlement-sources — name who pays out a tender family. The server moves that
 * family's already-accrued balance under the new payer in the same transaction, so every cached
 * balance view is stale afterwards.
 */
export function useSetSettlementSource(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: SettlementSourceConfig) =>
      apiFetch<void>('/api/v1/settlement-sources', {
        method: 'PUT',
        tenant: tenantOf(session),
        body,
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: sourceConfigKey(session) })
      void qc.invalidateQueries({ queryKey: sourcesKey(session) })
      void qc.invalidateQueries({ queryKey: overdueKey(session) })
    },
  })
}

/**
 * POST /api/v1/platform-settlements/{id}/void — take a recorded payout back. The settlement is
 * stamped and a contra entry posted (never deleted), and each line's gross returns to the
 * sub-ledger. 409 `platform-settlement-already-voided` when it has already been taken back.
 */
export function useVoidSettlement(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (settlementId: string) =>
      apiFetch<void>(`/api/v1/platform-settlements/${settlementId}/void`, {
        method: 'POST',
        tenant: tenantOf(session),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: sourcesKey(session) })
      void qc.invalidateQueries({ queryKey: overdueKey(session) })
      void qc.invalidateQueries({ queryKey: outstandingKey(session) })
      void qc.invalidateQueries({ queryKey: historyBaseKey(session) })
    },
  })
}

/**
 * POST /api/v1/platform-settlements/payouts — record ONE payout covering every source the payer
 * settled. Same idempotency contract as {@link useSettlePlatform}: fresh → 201, same-key replay of
 * the identical payload → 200, same key with DIFFERENT lines → 409. A line that would overdraw its
 * source → 422 and the whole payout is refused, leaving every source untouched.
 */
export function useSettlePayout(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ idempotencyKey, ...body }: SettlePayoutVariables) =>
      apiFetch<PlatformSettlementRecord>('/api/v1/platform-settlements/payouts', {
        method: 'POST',
        tenant: tenantOf(session),
        headers: { 'Idempotency-Key': idempotencyKey },
        body,
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: sourcesKey(session) })
      void qc.invalidateQueries({ queryKey: overdueKey(session) })
      void qc.invalidateQueries({ queryKey: outstandingKey(session) })
      void qc.invalidateQueries({ queryKey: historyBaseKey(session) })
    },
  })
}

/** GET /api/v1/platform-settlements/outstanding — every channel's outstanding receivable. */
export function useOutstanding(session: CompanySession) {
  return useQuery({
    queryKey: outstandingKey(session),
    queryFn: async () => {
      const result = await apiFetch<PlatformOutstanding[]>(
        '/api/v1/platform-settlements/outstanding',
        { tenant: tenantOf(session) },
      )
      return result ?? []
    },
  })
}

/**
 * GET /api/v1/platform-settlements?channelCode= — settlement history, most recent first (capped
 * 100 server-side). `channelCode` omitted (or empty) fetches every channel.
 */
export function useSettlementHistory(session: CompanySession, channelCode?: string) {
  return useQuery({
    queryKey: historyKey(session, channelCode),
    queryFn: async () => {
      const result = await apiFetch<PlatformSettlementRecord[]>('/api/v1/platform-settlements', {
        tenant: tenantOf(session),
        query: { channelCode },
      })
      return result ?? []
    },
  })
}

/**
 * POST /api/v1/platform-settlements — record one payout. The backend REQUIRES an Idempotency-Key
 * header (a keyless request is a 400) and de-dupes retries of that key: fresh → 201, same-key
 * replay of the identical payload → 200, same key with a DIFFERENT payload → 409
 * `platform-settlement-idempotency-key-conflict`. `net > gross` → 422
 * `platform-settlement-net-exceeds-gross`; gross exceeding the channel's outstanding → 422
 * `platform-settlement-over-settlement`.
 */
export function useSettlePlatform(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ idempotencyKey, ...body }: SettlePlatformVariables) =>
      apiFetch<PlatformSettlementRecord>('/api/v1/platform-settlements', {
        method: 'POST',
        tenant: tenantOf(session),
        headers: { 'Idempotency-Key': idempotencyKey },
        body,
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: outstandingKey(session) })
      void qc.invalidateQueries({ queryKey: historyBaseKey(session) })
    },
  })
}
