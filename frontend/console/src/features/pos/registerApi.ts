/**
 * registerApi.ts — register sessions / closing kasir (ADR 0036).
 *
 * The cashier opens the drawer with a counted float and closes it with a physical recount; the
 * SERVER computes expected cash and the signed over/short and emits the variance to finance.
 * Open mints a fresh UUID Idempotency-Key per attempt; close uses the STABLE key
 * `close:<sessionId>` (review W4) so a retry after a lost response replays the original 200 —
 * a per-attempt key would make the server's replay path unreachable. A 409 on close means the
 * session was already closed under a different request (changed count, other device): refetch
 * the truth instead of retrying. Mutations are DISABLED offline by the caller (closing over
 * unsynced cash would understate expected cash — ADR 0028).
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch } from '@/lib/api'
import { useSession, type CompanySession } from '@/lib/session'
import { useResolvedOutlets } from '@/features/org/useResolvedOutlets'
import { lastClosedSession } from './lib/registerFloat'
import { registerMenuLabelKey, type RegisterMenuLabelKey } from './lib/registerGate'

export interface RegisterSessionResponse {
  id: string
  businessId: string
  status: 'OPEN' | 'CLOSED'
  businessDate: string
  openedAt: string
  openingFloatMinor: number
  currency: string
  closedAt: string | null
  cashSalesMinor: number | null
  cashRefundsMinor: number | null
  expectedCashMinor: number | null
  countedCashMinor: number | null
  overShortMinor: number | null
}

/** One tender's expected amount in the open window (ADR 0038 daily close v2). */
export interface TenderExpected {
  tenderType: 'CASH' | 'CARD' | 'QRIS' | 'ONLINE'
  expectedMinor: number
}

/** The live per-tender expected breakdown for an OPEN session (a preview for the close screen). */
export interface RegisterExpectedResponse {
  sessionId: string
  businessId: string
  currency: string
  asOf: string
  tenders: TenderExpected[]
}

/**
 * One settlement line's GROSS sales (before refunds) on the daily summary — mirror of
 * restaurant-service TenderSalesLine. `GIFT_CARD` is the 5th settlement type (gift-card redemption),
 * present only when gift cards were redeemed. Σ `salesMinor` == `totalMinor`.
 */
export interface TenderSalesLine {
  tenderType: 'CASH' | 'CARD' | 'QRIS' | 'ONLINE' | 'GIFT_CARD'
  salesMinor: number
}

/**
 * The POS daily transaction summary (Z-report) for a register session — mirror of restaurant-service
 * RegisterSummaryResponse. Works for an OPEN session (a live X-report over `[openedAt, now)`) and a
 * CLOSED one (the final Z-report over `[openedAt, closedAt)`). Reporting only: the tax line is PB1
 * ("Pajak Restoran", not PPN) and MUST be badged "estimasi" when `usesIllustrativeRules` is true.
 * Revenue breakdown reconciles: `grossSalesMinor − discountMinor − loyaltyRedeemedMinor +
 * serviceChargeMinor + taxMinor == totalMinor`; `netSalesMinor == totalMinor − refundsMinor`.
 */
export interface RegisterSummaryResponse {
  sessionId: string
  businessId: string
  status: 'OPEN' | 'CLOSED'
  businessDate: string
  currency: string
  openedAt: string
  asOf: string
  transactionCount: number
  grossSalesMinor: number
  discountMinor: number
  loyaltyRedeemedMinor: number
  serviceChargeMinor: number
  taxMinor: number
  totalMinor: number
  refundsMinor: number
  netSalesMinor: number
  usesIllustrativeRules: boolean
  tenders: TenderSalesLine[]
  openingFloatMinor: number
  expectedCashMinor: number | null
  countedCashMinor: number | null
  overShortMinor: number | null
}

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

const currentKey = (s: CompanySession) => ['register-session', s.companyId, s.businessId]

/** The outlet's OPEN session (null when the drawer is closed — the endpoint returns 204). */
export function useCurrentRegisterSession(session: CompanySession) {
  return useQuery({
    queryKey: currentKey(session),
    queryFn: () =>
      apiFetch<RegisterSessionResponse | null>('/api/v1/register-sessions/current', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId },
      }),
  })
}

/**
 * The OPEN session's live per-tender expected breakdown (ADR 0038) — cash/card/QRIS/online — shown
 * on the close screen so the cashier sees what to count/verify per tender. A preview; the close
 * still snapshots the authoritative figures server-side.
 */
export function useRegisterExpected(
  session: CompanySession,
  sessionId: string | null | undefined,
  enabled: boolean,
) {
  return useQuery({
    queryKey: ['register-expected', session.companyId, sessionId],
    enabled: enabled && !!sessionId,
    queryFn: () =>
      apiFetch<RegisterExpectedResponse>(`/api/v1/register-sessions/${sessionId}/expected`, {
        tenant: tenantOf(session),
      }),
  })
}

/**
 * The POS daily transaction summary (Z-report) for a session — the day's sales aggregates
 * (transaction count, gross/discount/service/tax breakdown, per-tender net) + the cash
 * reconciliation, over `[openedAt, closedAt ?? now)`. Works for an OPEN session (live X-report) and
 * a CLOSED one (final Z-report), so the till-menu "today's summary" reads it for the current open OR
 * last-closed session, and the close verdict reads it for the just-closed session.
 */
export function useRegisterSummary(
  session: CompanySession,
  sessionId: string | null | undefined,
  enabled: boolean,
) {
  return useQuery({
    queryKey: ['register-summary', session.companyId, sessionId],
    enabled: enabled && !!sessionId,
    queryFn: () =>
      apiFetch<RegisterSummaryResponse>(`/api/v1/register-sessions/${sessionId}/summary`, {
        tenant: tenantOf(session),
      }),
  })
}

/**
 * The outlet's most recent CLOSED session with a recorded count — the open form's float default
 * ("cash stays in the drawer overnight": yesterday's counted cash is today's likely float).
 * Served from the history endpoint (newest-first); enabled only while the open form is showing.
 */
export function useLastClosedSession(session: CompanySession, enabled: boolean) {
  return useQuery({
    queryKey: ['register-last-closed', session.companyId, session.businessId],
    enabled,
    queryFn: async () => {
      const rows = await apiFetch<RegisterSessionResponse[]>('/api/v1/register-sessions', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId },
      })
      return lastClosedSession(rows)
    },
  })
}

/**
 * The register entry's label key for a surface with NO POS `session` in scope — the manager
 * "Lainnya" (More) sheet tile. It resolves the CURRENT effective outlet (the same hook OutletGate
 * uses) then reads that outlet's open session, SHARING the POS/RegisterSheet cache via the identical
 * query key (`currentKey`), so it costs no extra round trip when the till already loaded it. Returns
 * the neutral combined label until the outlet AND the session read have BOTH settled — the tile
 * never flashes the wrong action. Pass `active=false` (tile hidden) to skip the session read.
 */
export function useCurrentOutletRegisterLabelKey(active: boolean): RegisterMenuLabelKey {
  const { company } = useSession()
  const { effectiveOutletId, status } = useResolvedOutlets()
  const enabled = active && !!company && !!effectiveOutletId
  const query = useQuery({
    enabled,
    queryKey: ['register-session', company?.companyId, effectiveOutletId],
    queryFn: () =>
      apiFetch<RegisterSessionResponse | null>('/api/v1/register-sessions/current', {
        tenant: { companyId: company!.companyId, actor: company!.actor },
        query: { businessId: effectiveOutletId! },
      }),
  })
  // Only trust an open/closed verdict once the outlet is 'ready' AND the session read has settled;
  // any other state (loading/error/empty outlet, or an in-flight session read) → neutral label.
  return registerMenuLabelKey({
    isLoading: status !== 'ready' || query.isLoading,
    isError: query.isError,
    session: query.data,
  })
}

export function useOpenRegisterSession(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ openingFloatMinor, currency }: { openingFloatMinor: number; currency: string }) =>
      apiFetch<RegisterSessionResponse>('/api/v1/register-sessions', {
        method: 'POST',
        tenant: tenantOf(session),
        headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: { businessId: session.businessId, openingFloatMinor, currency },
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: currentKey(session) }),
  })
}

/** A cashier's counted/settled amount for one non-cash tender at close (ADR 0038 phase 2). */
export interface TenderCountInput {
  tenderType: 'CARD' | 'QRIS' | 'ONLINE'
  countedMinor: number
}

export function useCloseRegisterSession(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      sessionId,
      countedCashMinor,
      tenderCounts,
    }: {
      sessionId: string
      countedCashMinor: number
      tenderCounts?: TenderCountInput[]
    }) =>
      apiFetch<RegisterSessionResponse>(`/api/v1/register-sessions/${sessionId}/close`, {
        method: 'POST',
        tenant: tenantOf(session),
        headers: { 'Idempotency-Key': `close:${sessionId}` },
        body: { countedCashMinor, tenderCounts },
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: currentKey(session) }),
    onError: (err) => {
      // Already closed (double-close race or a changed recount after a lost response): the
      // server state is the truth — refetch so the sheet flips out of the close form.
      if (err instanceof ApiError && err.status === 409) {
        void qc.invalidateQueries({ queryKey: currentKey(session) })
      }
    },
  })
}
