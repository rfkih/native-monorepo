import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch as apiFetchBase, type RequestOptions } from '@/lib/api'

/**
 * ADR 0049 P3b — every call in this module targets a DASHBOARD_ROLES-gated back-office route
 * (`/api/v1/tax/**`), so it always uses the PERSONAL bearer (the elevation token on a device
 * terminal; identical to the single login token for a normal `user` login). Shadows the shared
 * `apiFetch` import so every call site below is correct with ZERO per-call changes.
 */
function apiFetch<T>(path: string, opts: RequestOptions = {}) {
  return apiFetchBase<T>(path, { ...opts, auth: 'personal' })
}

/**
 * Tax / PPN (VAT) — typed client for finance-service's tax endpoints (all via the gateway,
 * owner/manager only). Money on the wire is always integer minor units + an ISO-4217 currency
 * string (rule 8) — never a float. The PPN return is GL-derived (output VAT − input VAT = net);
 * filing posts the period-end netting entry and seals the period; settling pays a net-payable
 * return. ILLUSTRATIVE / SME-gated (rate, carryforward policy, e-Faktur layout) — see ADR 0017.
 */

export type NetDirection = 'PAYABLE' | 'CREDITABLE'
export type TaxFilingStatus = 'FILED' | 'SETTLED'

/** GET /api/v1/tax/vat/return — the PPN report (live figures, or the filed snapshot once filed). */
export interface VatReturn {
  period: string
  currency: string | null
  outputVatMinor: number
  inputVatMinor: number
  netMinor: number
  netDirection: NetDirection
  filed: boolean
  filingId: string | null
  filingStatus: TaxFilingStatus | null
}

/** A filed PPN return (detail + each history row). */
export interface TaxFiling {
  id: string
  period: string
  taxType: string
  status: TaxFilingStatus
  currency: string
  outputVatMinor: number
  inputVatMinor: number
  netMinor: number
  netDirection: NetDirection
  filingEntryId: string
  settlementEntryId: string | null
  filedAt: string
  settledAt: string | null
}

export interface TaxFilingHistory {
  filings: TaxFiling[]
}

/** One output tax invoice in the e-Faktur export. */
export interface EfakturLine {
  invoiceNumber: string
  issueDate: string
  customerName: string
  customerTaxId: string | null
  dppMinor: number
  ppnMinor: number
  totalMinor: number
  currency: string
}

export interface EfakturExport {
  period: string
  lines: EfakturLine[]
}

/** GET /api/v1/tax/vat/return?period=YYYY-MM. */
export function useVatReturn(params: {
  companyId: string
  actor: string
  period: string
  enabled: boolean
}) {
  const { companyId, actor, period, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['taxReturn', companyId, period],
    queryFn: () =>
      apiFetch<VatReturn>('/api/v1/tax/vat/return', {
        tenant: { companyId, actor },
        query: { period },
      }),
  })
}

/** GET /api/v1/tax/vat/returns — the filing history for the bound company. */
export function useTaxFilings(params: { companyId: string; actor: string; enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['taxFilings', companyId],
    queryFn: async () => {
      const result = await apiFetch<TaxFilingHistory>('/api/v1/tax/vat/returns', {
        tenant: { companyId, actor },
      })
      return result?.filings ?? []
    },
  })
}

function invalidateTax(
  queryClient: ReturnType<typeof useQueryClient>,
  companyId: string,
) {
  // Partial-key match — covers ['taxReturn', companyId, period] for any period.
  void queryClient.invalidateQueries({ queryKey: ['taxReturn', companyId] })
  void queryClient.invalidateQueries({ queryKey: ['taxFilings', companyId] })
}

/** POST /api/v1/tax/vat/returns — file the return for a period (posts the netting, seals). */
export function useFileReturn(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (period: string) =>
      apiFetch<TaxFiling>('/api/v1/tax/vat/returns', {
        method: 'POST',
        tenant: { companyId, actor },
        body: { period },
      }),
    onSuccess: () => invalidateTax(queryClient, companyId),
  })
}

/** POST /api/v1/tax/vat/returns/{id}/settle — pay a net-payable return to the tax office. */
export function useSettleReturn(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (filingId: string) =>
      apiFetch<TaxFiling>(`/api/v1/tax/vat/returns/${filingId}/settle`, {
        method: 'POST',
        tenant: { companyId, actor },
      }),
    onSuccess: () => invalidateTax(queryClient, companyId),
  })
}

/**
 * GET /api/v1/tax/vat/efaktur?period= — fetch the period's output tax invoices for a CSV download.
 * Called on demand by the export button (not a standing query), then rendered to CSV client-side.
 */
export function fetchEfaktur(params: {
  companyId: string
  actor: string
  period: string
}): Promise<EfakturExport | null> {
  const { companyId, actor, period } = params
  return apiFetch<EfakturExport>('/api/v1/tax/vat/efaktur', {
    tenant: { companyId, actor },
    query: { period },
  })
}
