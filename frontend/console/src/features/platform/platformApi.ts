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
import { apiFetch } from '@/lib/api'
import type { CompanySession } from '@/lib/session'

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
