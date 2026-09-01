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
  /** The company's business vertical (ADR 0070 moved it up from the org unit). */
  vertical: string
}

export interface CompanyResponse {
  id: string
  name: string
  baseCurrency: string
  defaultLanguage: string
  legalEmployerId: string
  /** The id of the outlet the bootstrap seeded (ADR 0070: exactly one). */
  firstBusinessId: string
  /** Optional — an older server (pre P1 tier-mode) omits this column; callers fail OPEN to FULL
   *  via `toPlanTier` (lib/featureTier.ts), never hiding a feature on a read gap. */
  planTier?: string
  /** The company's login-namespace code (ADR 0054); absent on an older server. */
  companyCode?: string
  /**
   * The company's business vertical (ADR 0070 moved it up from the org unit). Load-bearing for the
   * session: since the vertical is now the SOLE POS discriminator, a session created without it
   * falls open to 'restaurant' — which would route a brand-new carwash company to the restaurant
   * POS until /companies/mine refetched (and permanently in dev, where the session is persisted).
   */
  vertical?: string | null
}

/**
 * POST /api/v1/companies — the tenant bootstrap (X-Actor only; the new companyId is server-side).
 * ADR 0049 P3b: DASHBOARD_ROLES-gated + tenant-optional (bootstrap), so it uses the PERSONAL bearer
 * — the elevation token on a device terminal (the new company attaches to the actual PERSON's
 * identity, never the device's); identical to the single login token for a normal `user` login.
 */
export async function createCompany(
  body: CreateCompanyRequest,
  actor: string,
): Promise<CompanyResponse> {
  const res = await apiFetch<CompanyResponse>('/api/v1/companies', {
    method: 'POST',
    body,
    actor,
    auth: 'personal',
  })
  if (!res) throw new Error('Empty response from create-company')
  return res
}
