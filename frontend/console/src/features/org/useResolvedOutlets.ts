/**
 * useResolvedOutlets — the single source of truth for "which outlet is this terminal on".
 *
 * Hoisted out of OutletPicker (ADR 0012) so that BOTH the picker and the OutletGate share
 * one resolution: company outlets ∩ the caller's own assignments, the outlets[0] self-heal,
 * and an explicit status the gate can render on. Keeping the self-heal here (not inside the
 * gated content) avoids the deadlock where the gate waits for an activeOutletId that only a
 * component behind the gate could set.
 */

import { useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import { useSession } from '@/lib/session'
import { useOutlets, type OutletSummary } from './api'

/**
 * GET /api/v1/users/me/outlets — the signed-in user's own outlet assignments.
 * Allowed for all POS roles. Returns null (not an empty array) on any error so
 * callers can distinguish "load failed" from "user has an empty assignment set".
 */
export function useMyOutlets(companyId: string | null, actor: string) {
  return useQuery<OutletSummary[] | null>({
    enabled: !!companyId,
    queryKey: ['myOutlets', companyId],
    retry: false,
    queryFn: async () => {
      try {
        const result = await apiFetch<Array<{ orgUnitId: string; name: string }>>(
          '/api/v1/users/me/outlets',
          { tenant: { companyId: companyId!, actor } },
        )
        if (!result) return null
        // Normalise to OutletSummary shape (orgUnitId → id)
        return result.map((r) => ({ id: r.orgUnitId, name: r.name }))
      } catch {
        return null
      }
    },
  })
}

export type ResolvedOutletsStatus = 'loading' | 'error' | 'empty' | 'ready'

export interface ResolvedOutlets {
  /** The outlets this user may ring on (company outlets ∩ own assignments). */
  outlets: OutletSummary[]
  /** A REAL outlet id once status is 'ready'; never the business-unit id. Null otherwise. */
  effectiveOutletId: string | null
  status: ResolvedOutletsStatus
  refetch: () => void
}

export function useResolvedOutlets(): ResolvedOutlets {
  const { company, activeOutletId, setActiveOutlet } = useSession()
  const outletsQuery = useOutlets(company?.companyId ?? null, company?.actor ?? '')
  const myOutletsQuery = useMyOutlets(company?.companyId ?? null, company?.actor ?? '')

  const allOutlets = outletsQuery.data ?? []
  // myOutlets is null when the request errored; an empty array means unrestricted.
  const myOutlets = myOutletsQuery.data

  /**
   * Intersection logic:
   * - If "me" returned a NON-EMPTY list: restrict to only those outlets (by id, preserving
   *   the name ordering from the company outlet list so the picker stays sorted).
   * - If "me" returned an EMPTY list OR errored (null): show the full company list.
   */
  const outlets: OutletSummary[] =
    myOutlets && myOutlets.length > 0
      ? allOutlets.filter((o) => myOutlets.some((m) => m.id === o.id))
      : allOutlets

  // Self-heal: if the stored outlet id is absent from the RESTRICTED list, default to
  // outlets[0] and persist it — the terminal is never left ambiguous.
  useEffect(() => {
    if (outlets.length === 0) return
    const valid = outlets.some((o) => o.id === activeOutletId)
    if (!valid) {
      setActiveOutlet(outlets[0].id)
    }
  }, [outlets, activeOutletId, setActiveOutlet])

  const isLoading = !company || outletsQuery.isPending || myOutletsQuery.isPending
  const status: ResolvedOutletsStatus = isLoading
    ? 'loading'
    : outletsQuery.isError
      ? 'error'
      : outlets.length === 0
        ? 'empty'
        : 'ready'

  // While the self-heal effect has not fired yet, fall back to outlets[0] synchronously so
  // the first gated render already carries a real outlet id.
  const effectiveOutletId =
    status === 'ready'
      ? (outlets.find((o) => o.id === activeOutletId)?.id ?? outlets[0].id)
      : null

  return {
    outlets,
    effectiveOutletId,
    status,
    refetch: () => {
      void outletsQuery.refetch()
      void myOutletsQuery.refetch()
    },
  }
}
