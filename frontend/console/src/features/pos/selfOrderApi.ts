/**
 * Self-order access tokens (Phase 6, ADR 0029) — MANAGEMENT side only (owner/manager). One active
 * token per restaurant table plus one table-less "kiosk / counter" token, all sharing a `kid` (key
 * id) the backend rotates atomically for the whole outlet. The self-order mini app
 * (frontend/self-order) never calls this endpoint — it only ever RECEIVES one token via its `?t=`
 * URL param (baked into the printed QR code by SelfOrderQr.tsx).
 *
 * Backend contract — confirmed against
 * services/restaurant-service/.../selforderaccess/{controller,dto}/* (landed in this same tree
 * while this app was being built; the wire shape below matches it exactly, kept in ONE place so any
 * future drift is a one-line fix):
 *   GET  /api/v1/self-order-access?outletId=         → SelfOrderAccessResponse
 *   POST /api/v1/self-order-access/rotate {outletId} → SelfOrderAccessResponse (fresh tokens)
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import type { CompanySession } from '@/lib/session'

/** One minted, table-scoped self-order token — mirrors backend `SelfOrderTableToken`. */
export interface SelfOrderTableToken {
  tableId: string
  tableLabel: string
  token: string
}

/** Mirrors backend `SelfOrderAccessResponse` exactly. */
export interface SelfOrderAccessResponse {
  kid: string
  /** A token with no table claim, for a stand-alone ordering kiosk. */
  kioskToken: string
  tables: SelfOrderTableToken[]
}

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

function queryKey(session: CompanySession, outletId: string) {
  return ['self-order-access', session.companyId, outletId]
}

export function useSelfOrderAccess(session: CompanySession, outletId: string) {
  return useQuery({
    queryKey: queryKey(session, outletId),
    queryFn: () =>
      apiFetch<SelfOrderAccessResponse>('/api/v1/self-order-access', {
        tenant: tenantOf(session),
        query: { outletId },
      }),
    staleTime: 60_000,
  })
}

export function useRotateSelfOrderAccess(session: CompanySession, outletId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () =>
      apiFetch<SelfOrderAccessResponse>('/api/v1/self-order-access/rotate', {
        method: 'POST',
        tenant: tenantOf(session),
        body: { outletId },
      }),
    onSuccess: (res) => {
      if (res) qc.setQueryData(queryKey(session, outletId), res)
    },
  })
}
