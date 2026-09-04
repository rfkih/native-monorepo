/**
 * ingredientStocktakeApi.ts — the ingredient stock opname (ADR 0046 phase 1). The operator
 * counts every active ingredient; the SERVER compares the count to the system quantity,
 * adjusts stock to match, and books valued shrinkage (`Dr INVENTORY_SHRINKAGE / Cr
 * INVENTORY`, the SAME StocktakeCompleted event as the legacy menu-item flow) for
 * ingredients that carry a cost — uncosted ingredients are counted operationally but post
 * no journal, and a count with ZERO costed lines posts nothing at all (`currency` null).
 *
 * Idempotency: a STABLE key held across retries of one count, minted fresh only after a
 * success — a fresh-key-per-attempt (like register OPEN) would let a lost-response retry
 * create a SECOND opname → double stock adjustment + double shrinkage. Holding the key means
 * a retry replays the original; resetting on success makes the NEXT count distinct.
 *
 * Money rule (rule 8): integer minor units everywhere; renders via formatMoney.
 */
import { useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import type { CompanySession } from '@/lib/session'
import { INGREDIENTS_KEY } from './ingredientApi'

export interface IngredientStocktakeLineInput {
  ingredientId: string
  countedQty: number
}

/**
 * Sign convention (server-fixed, ADR 0038/0046): LINE `varianceValueMinor = varianceQty ×
 * unitCost`, so it carries the raw variance sign — NEGATIVE = shortage = loss. PARENT
 * `shrinkageMinor = −Σ varianceValueMinor` — POSITIVE = net loss (finance posts a loss as
 * Dr shrinkage). The UI reads `varianceQty` for per-line tone and the parent sign directly.
 */
export interface IngredientStocktakeLineResponse {
  ingredientId: string
  name: string
  unit: string
  systemQty: number
  countedQty: number
  varianceQty: number
  unitCostMinor: number | null
  varianceValueMinor: number
}

/** `currency` is null when no counted line carried a cost — nothing was (or will be) posted. */
export interface IngredientStocktakeResponse {
  id: string
  businessId: string
  currency: string | null
  countedAt: string
  shrinkageMinor: number
  lines: IngredientStocktakeLineResponse[]
}

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

/**
 * GET /api/v1/ingredient-stocktakes?businessId — the outlet's opname history, newest first (server
 * cap 50), each with its full lines. Only fetched while the riwayat sheet is open.
 */
export function useStocktakeHistory(session: CompanySession, enabled: boolean) {
  return useQuery({
    enabled,
    queryKey: ['ingredient-stocktakes', session.companyId, session.businessId],
    staleTime: 30_000,
    queryFn: () =>
      apiFetch<IngredientStocktakeResponse[]>('/api/v1/ingredient-stocktakes', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId },
      }),
  })
}

export function useSubmitIngredientStocktake(session: CompanySession) {
  const qc = useQueryClient()
  // Stable across retries of one count (replay-safe); reset only after a successful submit.
  const keyRef = useRef<string | null>(null)
  if (keyRef.current == null) keyRef.current = crypto.randomUUID()
  return useMutation({
    mutationFn: (lines: IngredientStocktakeLineInput[]) =>
      apiFetch<IngredientStocktakeResponse>('/api/v1/ingredient-stocktakes', {
        method: 'POST',
        tenant: tenantOf(session),
        headers: { 'Idempotency-Key': keyRef.current ?? crypto.randomUUID() },
        body: { businessId: session.businessId, lines },
      }),
    onSuccess: () => {
      // Quantities were adjusted to the physical count server-side — refresh the catalog.
      void qc.invalidateQueries({ queryKey: INGREDIENTS_KEY(session) })
      // The fresh count becomes the newest riwayat row.
      void qc.invalidateQueries({
        queryKey: ['ingredient-stocktakes', session.companyId, session.businessId],
      })
      // The next distinct count is a new submission — mint a fresh key.
      keyRef.current = crypto.randomUUID()
    },
  })
}
