/**
 * Pure helpers for the perpetual-inventory activation form (ADR 0067 §5, Phase D4) — no React, no
 * network, unit-tested directly (mirrors features/openingBalances/lines.ts's
 * `parseOpeningAmountInput` / features/expenses/amount.ts's `parseAmountInputToMinor` / features/
 * tax/format.ts's `taxErrorKey`). Activating perpetual inventory is a DELIBERATE, owner-only,
 * effectively irreversible accounting election (there is no deactivate/amend flow) — this module
 * only decides whether the two-step activation dialog is ready to ADVANCE/SUBMIT and how to map
 * the server's 409/422 refusals to a friendly message; the dialog's confirm step and the request
 * itself live in InventoryMethodSettings.tsx.
 */
import { ApiError } from '@/lib/api'
import { isoMinorExponent } from '@/lib/money'

/** Mirrors the backend's `@Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$")` on `cutoverPeriod`
 *  (finance-service `ActivatePerpetualInventoryRequest`) — kept in sync by hand since the two
 *  services never share code (rule 1, db/deploy-per-service). */
const CUTOVER_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/

/** The activation form's default cutover period — the current calendar month (device-local),
 *  formatted `YYYY-MM` for the native `<input type="month">`. An owner activating today almost
 *  always means "starting this month"; they can still pick an earlier one. */
export function defaultCutoverPeriod(now: Date = new Date()): string {
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  return `${year}-${month}`
}

/** Whether a typed/picked cutover period is a syntactically valid `YYYY-MM`. Client-side shape
 *  validation only — the server is the final authority (e.g. a SEALED period is refused there with
 *  a 422 that {@link activationErrorKey} maps separately). */
export function isValidCutoverPeriod(value: string): boolean {
  return CUTOVER_PATTERN.test(value.trim())
}

/**
 * Parses the counted opening inventory value (major units typed by the owner, e.g. "4500000" for
 * IDR or "450.00" for USD) into integer minor units for `currency` (rule 8 — money is always
 * integer minor units, never a float). Unlike most amount inputs in this codebase, ZERO is valid
 * here — a company with nothing counted on hand yet still activates, just without an opening
 * journal entry (mirrors the backend's `@Min(0)`). Returns `null` for blank, non-numeric, or
 * negative input.
 */
export function parseOpeningInventoryValueInput(value: string, currency: string): number | null {
  const trimmed = value.trim()
  if (trimmed === '') return null
  const exponent = isoMinorExponent(currency)
  // Zero-exponent currency (IDR): a '.'/',' can only be the Indonesian grouping-separator habit —
  // reject rather than mis-scale (features/expenses/amount.ts E6/E7 W2 lesson).
  if (exponent === 0 && /[.,]/.test(trimmed)) return null
  const major = Number(trimmed)
  if (!Number.isFinite(major) || major < 0) return null
  return Math.round(major * 10 ** exponent)
}

/** Whether the activation form (step 1 — cutover + opening value) is ready to advance to the
 *  confirm step: a syntactically valid cutover period and a parseable (possibly zero) opening
 *  value. */
export function canAdvanceActivationForm(
  cutoverPeriod: string,
  openingValueInput: string,
  currency: string,
): boolean {
  return (
    isValidCutoverPeriod(cutoverPeriod) &&
    parseOpeningInventoryValueInput(openingValueInput, currency) != null
  )
}

/**
 * Maps the finance-service `POST /api/v1/inventory-method/activate` RFC-7807 problem to a friendly
 * i18n key (mirrors features/tax/format.ts's `taxErrorKey` / features/ap/format.ts's
 * `billErrorKey`): already-active (409 — a stale double-submit or a second owner racing the same
 * election) and a sealed cutover period (422 — the requested month is already tax-filed) both get
 * their own actionable message; anything else falls back to a generic retry message.
 */
export function activationErrorKey(
  err: unknown,
):
  | 'settings.inventoryMethod.error.alreadyActive'
  | 'settings.inventoryMethod.error.sealedPeriod'
  | 'settings.inventoryMethod.error.generic' {
  if (err instanceof ApiError) {
    const type = err.problem?.type ?? ''
    if (err.status === 409 && type.includes('inventory-method-already-active')) {
      return 'settings.inventoryMethod.error.alreadyActive'
    }
    if (err.status === 422 && type.includes('inventory-method-sealed-period')) {
      return 'settings.inventoryMethod.error.sealedPeriod'
    }
  }
  return 'settings.inventoryMethod.error.generic'
}
