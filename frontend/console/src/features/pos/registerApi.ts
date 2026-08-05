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
import type { CompanySession } from '@/lib/session'

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

export function useCloseRegisterSession(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ sessionId, countedCashMinor }: { sessionId: string; countedCashMinor: number }) =>
      apiFetch<RegisterSessionResponse>(`/api/v1/register-sessions/${sessionId}/close`, {
        method: 'POST',
        tenant: tenantOf(session),
        headers: { 'Idempotency-Key': `close:${sessionId}` },
        body: { countedCashMinor },
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
