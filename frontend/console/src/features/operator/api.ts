/**
 * operator/api.ts — TanStack Query hooks for the Business-app till's operator identification (ADR
 * 0049 P3b/P3d): the roster the picker shows (`GET /api/v1/operators/roster`), the per-outlet
 * require-PIN policy (`GET /api/v1/operators/policy`), and the sign-in mutation (`POST
 * /api/v1/operators/session`) that mints the signed operator session — PIN-verified or, at a
 * no-PIN outlet, a bare pick. `features/operator/OperatorSessionProvider.tsx` persists the
 * mutation's result and arms `X-Operator-Session` (lib/api.ts) for every subsequent request — this
 * module only talks to the network, exactly like `features/pos/registerApi.ts`'s split from
 * `RegisterSheet.tsx`.
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

/** `POST /api/v1/operators/session` request body. */
interface OperatorSessionRequestBody {
  businessId: string
  employeeId: string
  pin?: string
}

/**
 * Pure request-body shaping (ADR 0049 P3d) — standalone (no `useMutation`, no fetch) so the
 * omit-vs-include shape is unit-testable without mocking the network, mirroring `selectBearerToken`
 * (lib/api.ts) and `operatorSignInRequired` (operatorGate.ts)'s own pure-core split. A blank/absent
 * `pin` is dropped from the body ENTIRELY — never sent as `pin: ''` — mirroring
 * `OperatorSessionRequest`'s own `@Nullable pin` server-side, which a no-PIN outlet ignores either
 * way, but the till still never sends a value it never collected.
 */
export function buildOperatorSessionBody(vars: {
  businessId: string
  employeeId: string
  pin?: string
}): OperatorSessionRequestBody {
  const { businessId, employeeId, pin } = vars
  return pin ? { businessId, employeeId, pin } : { businessId, employeeId }
}

/**
 * Verifies `(employeeId, pin)` at `businessId` and mints the signed operator session. Throws
 * (mirrored as the mutation's `error`) on a wrong/locked PIN, an employee not assigned to the
 * outlet, or an employee with no linked login — the caller (OperatorPinSheet) maps the ApiError's
 * status to a friendly message.
 *
 * `pin` is OPTIONAL (ADR 0049 P3d, per-outlet no-PIN policy) — see {@link buildOperatorSessionBody}
 * for the exact omit-vs-include shape.
 */
export function useOperatorSignInMutation(session: CompanySession | null) {
  return useMutation({
    mutationFn: (vars: { businessId: string; employeeId: string; pin?: string }) => {
      if (!session) throw new Error('no active company/outlet session')
      return apiFetch<OperatorSessionApiResponse>('/api/v1/operators/session', {
        method: 'POST',
        tenant: tenantOf(session),
        body: buildOperatorSessionBody(vars),
      })
    },
  })
}

/** `GET /api/v1/operators/policy` response — mirrors employee-service's
 * `OutletOperatorPolicyResponse` (also separately mirrored, owner/manager dashboard side, by
 * `features/terminal/api.ts`'s `OutletPinPolicy` — same shape, different bearer, kept as two
 * copies rather than a shared import so the two features stay decoupled). */
export interface OutletPinPolicy {
  requirePin: boolean
}

/**
 * Whether `businessId`'s operator sign-in currently requires a PIN (ADR 0049 P3d). Read by the
 * till itself (Pos.tsx / ServicePos.tsx), prefetched alongside the roster rather than lazily
 * inside `OperatorPinSheet` — by the time the sheet opens the answer is already in cache, so it
 * never flashes the PIN pad before flipping to the no-PIN picker. Sent on the OUTLET bearer
 * (`auth: 'outlet'`, POS_ROLES — the same credential the roster/mint calls above use), unlike the
 * owner-side dashboard's copy of this same endpoint (`features/terminal/api.ts`'s `usePinPolicy`,
 * PERSONAL bearer). Not a secret, so an ordinary cached query.
 *
 * Callers MUST default a missing/erroring read to `requirePin: true` (`data?.requirePin ?? true`)
 * — the server's own safe default — never assume no-PIN on a read failure.
 */
export function useOutletPinPolicy(
  session: CompanySession | null,
  businessId: string,
  enabled = true,
) {
  return useQuery({
    queryKey: ['operator-pin-policy', session?.companyId, businessId],
    enabled: enabled && !!session && !!businessId,
    queryFn: () =>
      apiFetch<OutletPinPolicy>('/api/v1/operators/policy', {
        tenant: tenantOf(session as CompanySession),
        auth: 'outlet',
        query: { businessId },
      }),
  })
}
