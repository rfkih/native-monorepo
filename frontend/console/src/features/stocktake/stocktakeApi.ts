/**
 * stocktakeApi.ts — inventory stocktake (ADR 0038 phase 3, "daily close v2"). The operator counts
 * every tracked menu item; the SERVER compares the count to the system quantity, adjusts stock to
 * match, and books valued shrinkage (`Dr INVENTORY_SHRINKAGE / Cr INVENTORY`) for items that carry
 * a `unitCostMinor` — items without one are counted operationally but post no journal. Modelled on
 * registerApi.ts.
 *
 * Idempotency: a STABLE key held across retries of one count, minted fresh only after a success. A
 * stocktake adjusts stock + posts shrinkage, so a fresh-key-per-attempt (like register OPEN) would
 * let a lost-response retry create a SECOND stocktake → double stock adjustment + double shrinkage.
 * Holding the key means a retry replays the original (the server returns the existing stocktake);
 * resetting on success means the operator's NEXT count is a distinct submission.
 *
 * Money rule (rule 8): unitCostMinor / varianceValueMinor / shrinkageMinor are integer minor units;
 * every render goes through formatMoney. Strings rule (rule 9): i18n keys only, no hand formatting.
 */
import { useRef } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import type { CompanySession } from '@/lib/session'

/** One counted line submitted to POST /api/v1/stocktakes. */
export interface StocktakeLineInput {
  menuItemId: string
  countedQty: number
}

/**
 * One item's result on the StocktakeResponse. `varianceValueMinor` is null-safe to read but only
 * meaningful when `unitCostMinor` is non-null — an uncosted item still reports `varianceQty` (it was
 * physically counted) but never a money impact.
 *
 * Sign convention (fixed by the server, ADR 0038 phase 3): at the LINE level `varianceValueMinor =
 * varianceQty × unitCost`, so it carries the raw variance sign — NEGATIVE = shortage = loss,
 * positive = overage = gain. At the PARENT level `shrinkageMinor = −Σ varianceValueMinor`, i.e. the
 * opposite sign — POSITIVE = net loss, negative = net gain (finance posts a loss as Dr shrinkage).
 * The UI reads `varianceQty` for the per-line gain/loss tone and `Math.abs(varianceValueMinor)` for
 * its magnitude (see StocktakeSheet's `lineTone`), and the parent `shrinkageMinor` sign directly.
 */
export interface StocktakeLineResponse {
  menuItemId: string
  name: string
  systemQty: number
  countedQty: number
  varianceQty: number
  unitCostMinor: number | null
  varianceValueMinor: number
}

/** SIGNED net loss across all valued lines — positive = loss/shrinkage, negative = net gain. */
export interface StocktakeResponse {
  id: string
  businessId: string
  currency: string
  countedAt: string
  shrinkageMinor: number
  lines: StocktakeLineResponse[]
}

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

export function useSubmitStocktake(session: CompanySession) {
  const qc = useQueryClient()
  // Stable across retries of one count (replay-safe); reset only after a successful submit.
  const keyRef = useRef<string | null>(null)
  if (keyRef.current == null) keyRef.current = crypto.randomUUID()
  return useMutation({
    mutationFn: (lines: StocktakeLineInput[]) =>
      apiFetch<StocktakeResponse>('/api/v1/stocktakes', {
        method: 'POST',
        tenant: tenantOf(session),
        headers: { 'Idempotency-Key': keyRef.current ?? crypto.randomUUID() },
        body: { businessId: session.businessId, lines },
      }),
    onSuccess: () => {
      // Stock quantities are adjusted to the physical count server-side — refresh the POS menu so
      // the cashier immediately sees the corrected stock (and any new sold-out items).
      void qc.invalidateQueries({ queryKey: ['menu', session.companyId, session.businessId] })
      // The next distinct count is a new submission — mint a fresh key.
      keyRef.current = crypto.randomUUID()
    },
  })
}
