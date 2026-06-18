import { apiFetch } from '@/lib/api'

export interface CreateCompanyRequest {
  name: string
  baseCurrency: string
  defaultLanguage: string
  firstBusiness: { name: string; type: string }
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
