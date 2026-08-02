/**
 * registerApi.ts — register sessions / closing kasir (ADR 0036).
 *
 * The cashier opens the drawer with a counted float and closes it with a physical recount; the
 * SERVER computes expected cash and the signed over/short and emits the variance to finance.
 * Mutations mint a fresh UUID Idempotency-Key per attempt (the payment-surface convention) and
 * are DISABLED offline by the caller (closing over unsynced cash would understate expected cash —
 * ADR 0028).
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
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
        headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: { countedCashMinor },
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: currentKey(session) }),
  })
}
