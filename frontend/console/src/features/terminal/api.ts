/**
 * features/terminal/api.ts — TanStack Query hooks for the outlet's Business-app till (Native
 * Console Web manager UI, two-app + outlet-terminal program, ADR 0049 P3a/P3b, Phase 2): the
 * device (terminal) credential lifecycle at org-service and the outlet's require-PIN operator
 * policy at employee-service. Every route these hit is DASHBOARD_ROLES (owner/manager only) at
 * the gateway, so every hook here always sends the PERSONAL bearer (`auth: 'personal'`) — mirrors
 * `features/hr/api.ts`'s shadowed `apiFetch` idiom so every call site is correct with zero
 * per-call changes.
 *
 * Credential hygiene (rule 6): {@link useRevealDeviceCredential} is deliberately a MUTATION, not a
 * `useQuery` — it must fire ONLY on an explicit "Show password" click (never auto-fetch/prefetch;
 * the server writes an audit log on every reveal), and its plaintext result must never enter the
 * long-lived TanStack Query cache. The caller (`TerminalCard`) holds the result in local component
 * state only and clears it on "Hide" or unmount (ordinary React state — nothing to persist, nothing
 * extra to clear). The same posture applies to create/reset, which also return a plaintext password
 * once. The employee-operator-PIN policy below carries no secret (just a boolean), so it is an
 * ordinary cached query.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch as apiFetchBase, ApiError, type RequestOptions } from '@/lib/api'

function apiFetch<T>(path: string, opts: RequestOptions = {}) {
  return apiFetchBase<T>(path, { ...opts, auth: 'personal' })
}

interface TenantParams {
  companyId: string
  actor: string
}

// ---------------------------------------------------------------------------
// Device (terminal) credential — org-service, /api/v1/org-units/{outletId}/device-credential
// ---------------------------------------------------------------------------

/**
 * Mirror of org-service `DeviceCredentialResponse` — a PLAINTEXT secret. Never log this; never
 * hold it anywhere beyond the calling component's local state (rule 6).
 */
export interface DeviceCredential {
  outletId: string
  username: string
  password: string
}

/** Detects the org-service 404 `device-credential-not-found` (no credential provisioned yet). */
export function isDeviceCredentialNotFound(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 404 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('device-credential-not-found')
  )
}

/** Detects the org-service 409 `device-credential-already-exists` (create called twice/raced). */
export function isDeviceCredentialAlreadyExists(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 409 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('device-credential-already-exists')
  )
}

/**
 * `GET .../device-credential` — reveals the stored username + decrypted password. A MUTATION (see
 * this module's header doc): the caller must invoke `.mutate()` only from an explicit "Show
 * password" user action, never on mount/render.
 */
export function useRevealDeviceCredential(params: TenantParams & { outletId: string }) {
  const { companyId, actor, outletId } = params
  return useMutation({
    mutationFn: () =>
      apiFetch<DeviceCredential>(`/api/v1/org-units/${outletId}/device-credential`, {
        tenant: { companyId, actor },
      }),
  })
}

/** `POST .../device-credential` — mints a new credential; 409 if one already exists for the outlet. */
export function useCreateDeviceCredential(params: TenantParams & { outletId: string }) {
  const { companyId, actor, outletId } = params
  return useMutation({
    mutationFn: () =>
      apiFetch<DeviceCredential>(`/api/v1/org-units/${outletId}/device-credential`, {
        method: 'POST',
        tenant: { companyId, actor },
      }),
  })
}

/** `POST .../device-credential/reset` — rotates the password; the new one is shown ONCE. */
export function useResetDeviceCredential(params: TenantParams & { outletId: string }) {
  const { companyId, actor, outletId } = params
  return useMutation({
    mutationFn: () =>
      apiFetch<DeviceCredential>(`/api/v1/org-units/${outletId}/device-credential/reset`, {
        method: 'POST',
        tenant: { companyId, actor },
      }),
  })
}

/** `DELETE .../device-credential` — permanently removes the outlet's terminal login. */
export function useDeleteDeviceCredential(params: TenantParams & { outletId: string }) {
  const { companyId, actor, outletId } = params
  return useMutation({
    mutationFn: () =>
      apiFetch<null>(`/api/v1/org-units/${outletId}/device-credential`, {
        method: 'DELETE',
        tenant: { companyId, actor },
      }),
  })
}

// ---------------------------------------------------------------------------
// Require-PIN policy — employee-service GET /api/v1/operators/policy + PUT outlet-pin-policy
// ---------------------------------------------------------------------------

/** Mirror of employee-service `OutletOperatorPolicyResponse`. */
export interface OutletPinPolicy {
  requirePin: boolean
}

/**
 * `GET /api/v1/operators/policy?businessId=` — whether the till's operator sign-in currently
 * requires a PIN at this outlet. Not a secret (just a boolean), so this is an ordinary cached
 * query — safe to fetch on mount, unlike the device credential above. An outlet with no policy
 * row on file reads as `requirePin: true` server-side (the safe default).
 */
export function usePinPolicy(params: TenantParams & { outletId: string; enabled: boolean }) {
  const { companyId, actor, outletId, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['terminalPinPolicy', companyId, outletId],
    queryFn: () =>
      apiFetch<OutletPinPolicy>('/api/v1/operators/policy', {
        tenant: { companyId, actor },
        query: { businessId: outletId },
      }),
  })
}

/** `PUT /api/v1/employees/outlet-pin-policy/{outletId}` — set the outlet's require-PIN policy. */
export function useSetPinPolicy(params: TenantParams) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ outletId, requirePin }: { outletId: string; requirePin: boolean }) =>
      apiFetch<null>(`/api/v1/employees/outlet-pin-policy/${outletId}`, {
        method: 'PUT',
        tenant: { companyId, actor },
        body: { requirePin },
      }),
    onSuccess: (_d, { outletId }) => {
      void queryClient.invalidateQueries({ queryKey: ['terminalPinPolicy', companyId, outletId] })
    },
  })
}
