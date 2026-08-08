/**
 * operator/api.ts — TanStack Query hooks for the Business-app till's operator identification (ADR
 * 0049 P3b): the roster the PIN picker shows (`GET /api/v1/operators/roster`) and the PIN-verify
 * mutation (`POST /api/v1/operators/session`) that mints the signed operator session.
 * `features/operator/OperatorSessionProvider.tsx` persists the mutation's result and arms
 * `X-Operator-Session` (lib/api.ts) for every subsequent request — this module only talks to the
 * network, exactly like `features/pos/registerApi.ts`'s split from `RegisterSheet.tsx`.
 */
import { useMutation, useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import type { CompanySession } from '@/lib/session'

/** `GET /api/v1/operators/roster` entry — name only (rule 6, no role/status/PII on the roster). */
export interface OperatorRosterEntry {
  employeeId: string
  displayName: string
}

/** `POST /api/v1/operators/session` response. */
export interface OperatorSessionApiResponse {
  /** The opaque, HMAC-signed operator token — sent back as `X-Operator-Session`. */
  operatorSession: string
  displayName: string
  role: string
  expiresAt: string
}

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

/**
 * The employees who can sign in as an operator at `businessId` right now. `session` may be null
 * only transiently (before `OutletGate` resolves) — the query stays disabled until both it and
 * `businessId` are present.
 */
export function useOperatorRoster(
  session: CompanySession | null,
  businessId: string,
  enabled = true,
) {
  return useQuery({
    queryKey: ['operator-roster', session?.companyId, businessId],
    enabled: enabled && !!session && !!businessId,
    queryFn: () =>
      apiFetch<OperatorRosterEntry[]>('/api/v1/operators/roster', {
        tenant: tenantOf(session as CompanySession),
        query: { businessId },
      }),
  })
}

/**
 * Verifies `(employeeId, pin)` at `businessId` and mints the signed operator session. Throws
 * (mirrored as the mutation's `error`) on a wrong/locked PIN, an employee not assigned to the
 * outlet, or an employee with no linked login — the caller (OperatorPinSheet) maps the ApiError's
 * status to a friendly message.
 */
export function useOperatorSignInMutation(session: CompanySession | null) {
  return useMutation({
    mutationFn: (vars: { businessId: string; employeeId: string; pin: string }) => {
      if (!session) throw new Error('no active company/outlet session')
      return apiFetch<OperatorSessionApiResponse>('/api/v1/operators/session', {
        method: 'POST',
        tenant: tenantOf(session),
        body: vars,
      })
    },
  })
}
