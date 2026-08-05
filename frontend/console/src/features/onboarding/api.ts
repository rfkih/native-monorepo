import { apiFetch } from '@/lib/api'

export interface CreateCompanyRequest {
  name: string
  /** ISO 3166-1 alpha-2 country code — the server DERIVES the base currency from this (ADR 0025). */
  country: string
  /**
   * The base currency, derived client-side from {@link country} for preview only. The server
   * re-derives it authoritatively and ignores this value (an API caller cannot pick a currency);
   * sent for backward compatibility with the older request shape.
   */
  baseCurrency: string
  defaultLanguage: string
  firstBusiness: { name: string; vertical: string }
}

export interface CompanyResponse {
  id: string
  name: string
  baseCurrency: string
  defaultLanguage: string
  legalEmployerId: string
  firstBusinessId: string
}

/** POST /api/v1/companies — the tenant bootstrap (X-Actor only; the new companyId is server-side). */
export async function createCompany(
  body: CreateCompanyRequest,
  actor: string,
): Promise<CompanyResponse> {
  const res = await apiFetch<CompanyResponse>('/api/v1/companies', {
    method: 'POST',
    body,
    actor,
  })
  if (!res) throw new Error('Empty response from create-company')
  return res
}
