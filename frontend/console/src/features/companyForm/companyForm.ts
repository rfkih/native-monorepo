/**
 * Shared building blocks for the two company-creation flows: the public sign-up
 * (`features/signup`, which also creates the owner account) and the in-app "add a company"
 * wizard (`features/onboarding`). Both render the SAME Company and Region steps and the SAME
 * review, so the experience can't drift between "your first company" and "your next company".
 *
 * This module holds the pure, framework-free pieces (constants, types, validation, currency
 * symbol); the React step components live in `./fields`. The user-facing copy is sourced from the
 * `signup.*` i18n namespace — the canonical, already-translated set — so there is a single wording
 * source for both flows (rule 9).
 */

/**
 * Step indexes shared by both flows. Company is always step 0 and Region step 1 in signup AND the
 * add-company wizard, so the review's "edit" pencil jumps to the same place in either.
 */
export const COMPANY_STEP = 0
export const REGION_STEP = 1

/** Supported default languages — the UI copy of the server's `SignupRequest` whitelist. */
export const LANGS = ['en', 'id'] as const

/** Supported business verticals — the UI copy of the server's whitelist; drives which POS an outlet gets. */
export const VERTICALS = ['restaurant', 'carwash', 'barbershop'] as const

/** The company facts both flows collect (signup layers owner-account fields on top of these). */
export interface CompanyBasics {
  companyName: string
  vertical: string
  country: string
  defaultLanguage: string
}

/** A field on the Company step that can fail the required-check. */
export type CompanyBasicsField = 'companyName'

/**
 * The required-field rule for the Company step — identical for signup and the add-company wizard.
 * Returns the fields that are blank so each flow can render its own localized "required" message.
 *
 * ADR 0070 dropped the separate "first business name": the org structure is flat, so the company
 * name IS the business name and the bootstrap seeds one outlet from it.
 */
export function invalidCompanyFields(basics: { companyName: string }): CompanyBasicsField[] {
  const missing: CompanyBasicsField[] = []
  if (!basics.companyName.trim()) missing.push('companyName')
  return missing
}

/** A single review row (used by the shared review). `step` is where its edit pencil jumps. */
export interface ReviewRow {
  label: string
  value: string
  step: number
  fixed?: boolean
  secret?: boolean
}

/**
 * The narrow-symbol currency glyph for a locale (e.g. `IDR → "Rp"`, `USD → "$"`), falling back to
 * the ISO code if the runtime can't resolve one. Used to preview the derived base currency.
 */
export function symbolOf(currency: string, locale: string): string {
  try {
    const parts = new Intl.NumberFormat(locale, {
      style: 'currency',
      currency,
      currencyDisplay: 'narrowSymbol',
    }).formatToParts(0)
    return parts.find((p) => p.type === 'currency')?.value ?? currency
  } catch {
    return currency
  }
}
