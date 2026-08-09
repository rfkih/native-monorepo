/**
 * Sales channels API — typed client for restaurant-service's company-managed channel roster
 * (ADR 0036 Phase B: "channels are company-managed CRUD"). A channel is the platform picked at
 * checkout when the cashier rings an ONLINE (GoFood/GrabFood-style) order — see
 * features/pos-shell/payment/ChannelListPanelView.tsx.
 *
 * Company-wide, not outlet-scoped (mirrors features/promotions/api.ts and features/loyalty/api.ts:
 * a `session: CompanySession` positional arg, keyed by companyId only) — used from BOTH the
 * /channels dashboard admin page (owner/manager) and the POS payment surfaces (any POS role reads
 * the roster to populate the ONLINE tender's channel picker).
 *
 * `code` is UPPERCASE and immutable once created — PATCH never accepts it (see
 * UpdateSalesChannelBody). Strings rule (rule 9): no hardcoded user-facing strings here — this is
 * data plumbing only.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch } from '@/lib/api'
import type { CompanySession } from '@/lib/session'

/** Mirrors backend SalesChannelResponse exactly. */
export interface SalesChannel {
  id: string
  code: string
  name: string
  active: boolean
}

/** POST /api/v1/sales-channels body. `code` is fixed forever once created. */
export interface CreateSalesChannelBody {
  code: string
  name: string
}

/** PATCH /api/v1/sales-channels/{id} body — code is NOT here; it is immutable. */
export interface UpdateSalesChannelBody {
  name?: string
  active?: boolean
}

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

function channelsKey(session: CompanySession) {
  return ['sales-channels', session.companyId]
}

/**
 * GET /api/v1/sales-channels — every channel for the bound tenant (active and inactive; the
 * dashboard roster shows both, the POS's ONLINE panel filters to active — see
 * pos-shell/payment/channelPicker.ts's `activeChannels`). Readable by any POS role.
 */
export function useSalesChannels(session: CompanySession, opts: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: channelsKey(session),
    enabled: opts.enabled ?? true,
    queryFn: async () => {
      const result = await apiFetch<SalesChannel[]>('/api/v1/sales-channels', {
        tenant: tenantOf(session),
      })
      return result ?? []
    },
  })
}

/**
 * POST /api/v1/sales-channels — owner/manager only (the gateway only requires POS_ROLES here;
 * restaurant-service itself re-checks owner/manager — see this file's header doc). ADR 0049 P3b:
 * only Channels.tsx/ChannelDialog.tsx (the back-office admin surface) call this, never the POS —
 * so it uses the PERSONAL bearer (the elevation token on a device terminal; identical to the single
 * login token for a normal `user` login) to satisfy that SERVICE-side check on an elevated device.
 */
export function useCreateSalesChannel(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateSalesChannelBody) =>
      apiFetch<SalesChannel>('/api/v1/sales-channels', {
        method: 'POST',
        tenant: tenantOf(session),
        auth: 'personal',
        body,
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: channelsKey(session) }),
  })
}

/**
 * PATCH /api/v1/sales-channels/{id} — edit name and/or flip active (the soft-deactivate; a
 * deactivated channel drops out of the POS's ONLINE picker but stays in the dashboard roster for
 * reactivation). Owner/manager only. `id` travels with the mutate-time variables (not bound at
 * hook-creation time) so ONE hook instance serves every row of a list, mirroring
 * features/promotions/api.ts's usePatchPromoRule/usePatchCoupon.
 */
export function useUpdateSalesChannel(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: UpdateSalesChannelBody & { id: string }) =>
      apiFetch<SalesChannel>(`/api/v1/sales-channels/${id}`, {
        method: 'PATCH',
        tenant: tenantOf(session),
        // ADR 0049 P3b — see useCreateSalesChannel's doc (same owner/manager service-side check).
        auth: 'personal',
        body,
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: channelsKey(session) }),
  })
}

/**
 * Detects the 409 `sales-channel-code-conflict` problem+json (a duplicate code on create). The
 * dialog surfaces the API's own error message on this (and any other) failure rather than a
 * separate mapped i18n key — see ChannelDialog.tsx.
 */
export function isSalesChannelCodeConflict(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 409 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('sales-channel-code-conflict')
  )
}
