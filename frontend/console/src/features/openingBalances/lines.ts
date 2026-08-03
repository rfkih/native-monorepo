/**
 * Pure helpers for the opening-balances form (ADR 0037) — no React, no network, unit-tested
 * directly (mirrors features/platform/amount.ts / features/expenses/amount.ts). Amounts are typed
 * in MAJOR units and converted to integer MINOR units via `isoMinorExponent` (rule 8) — the shared
 * currency-aware helper every money form reuses, never a hand-rolled exponent table.
 */
import { isoMinorExponent } from '@/lib/money'
import type { OpeningBalanceLine, OpeningBalanceSide, RegisterBroughtForwardAssetBody } from './api'

/** One fixed balance-sheet line the form always shows, mapped to its account code + the side an
 *  entered amount posts to (the illustrative chart seeded by finance-service V46/V25/V28/V30). */
export interface LineFieldConfig {
  code: string
  side: OpeningBalanceSide
  labelKey: string
}

export const LINE_FIELDS: LineFieldConfig[] = [
  { code: '1900', side: 'DEBIT', labelKey: 'cashLabel' },
  { code: '1000', side: 'DEBIT', labelKey: 'bankLabel' },
  { code: '1200', side: 'DEBIT', labelKey: 'accountsReceivableLabel' },
  { code: '2000', side: 'CREDIT', labelKey: 'accountsPayableLabel' },
  { code: '1100', side: 'DEBIT', labelKey: 'inventoryLabel' },
  { code: '2700', side: 'CREDIT', labelKey: 'otherLiabilitiesLabel' },
  { code: '3000', side: 'CREDIT', labelKey: 'ownersCapitalLabel' },
  { code: '3100', side: 'CREDIT', labelKey: 'retainedEarningsLabel' },
]

/** Groups {@link LINE_FIELDS} into the labeled sections the FORM spec (ADR 0037) lays out — a
 *  presentation-only grouping (the account codes + sides above are the single source of truth). */
export const LINE_SECTIONS: { titleKey: string; codes: string[] }[] = [
  { titleKey: 'sectionCashBank', codes: ['1900', '1000'] },
  { titleKey: 'sectionReceivablesPayables', codes: ['1200', '2000'] },
  { titleKey: 'sectionInventory', codes: ['1100'] },
  { titleKey: 'sectionLoans', codes: ['2700'] },
  { titleKey: 'sectionCapital', codes: ['3000', '3100'] },
]

/**
 * Parses a user-typed amount (major units, e.g. "45000" typed for IDR, or "12.50" for USD) into
 * integer minor units for `currency` (rule 8 — money on the wire is always integer minor units).
 * Returns `null` for anything that is not a finite, non-negative number — blank input, "abc", a
 * negative amount, and (unless `allowZero`) zero.
 *
 * Zero-exponent currencies (IDR) reject a `.`/`,` outright rather than mis-scale: "45.000" typed as
 * the Indonesian grouping-separator habit for forty-five thousand must never parse as
 * `Number("45.000") === 45` (the features/expenses/amount.ts E6/E7 W2 lesson).
 */
export function parseOpeningAmountInput(
  value: string,
  currency: string,
  options: { allowZero?: boolean } = {},
): number | null {
  const trimmed = value.trim()
  if (trimmed === '') return null
  const exponent = isoMinorExponent(currency)
  if (exponent === 0 && /[.,]/.test(trimmed)) return null
  const major = Number(trimmed)
  if (!Number.isFinite(major) || major < 0) return null
  if (!options.allowZero && major === 0) return null
  return Math.round(major * 10 ** exponent)
}

/**
 * Builds the POST /api/v1/opening-balances `lines` array from the form's major-unit input values —
 * one entry per {@link LINE_FIELDS} row that parses to a positive amount. A blank or zero field is
 * OMITTED entirely (never sent as a zero line — the backend's `@Positive` would reject it, and an
 * omitted balance-sheet item simply means "not applicable to this business").
 */
export function buildOpeningBalanceLines(
  values: Record<string, string>,
  currency: string,
): OpeningBalanceLine[] {
  const lines: OpeningBalanceLine[] = []
  for (const field of LINE_FIELDS) {
    const amountMinor = parseOpeningAmountInput(values[field.code] ?? '', currency)
    if (amountMinor != null && amountMinor > 0) {
      lines.push({ accountCode: field.code, amountMinor, side: field.side })
    }
  }
  return lines
}

function debitTotalMinor(lines: OpeningBalanceLine[]): number {
  return lines.filter((l) => l.side === 'DEBIT').reduce((sum, l) => sum + l.amountMinor, 0)
}

function creditTotalMinor(lines: OpeningBalanceLine[]): number {
  return lines.filter((l) => l.side === 'CREDIT').reduce((sum, l) => sum + l.amountMinor, 0)
}

// -------------------------------------------------------------------------------------------------
// Fixed-asset rows (brought-forward assets — POST /api/v1/assets/opening). Each posts its OWN
// self-balancing entry (Dr cost / Cr accumulated depreciation / Cr opening balance equity) SEPARATE
// from the main opening-balance entry, so it is never itself an `OpeningBalanceLine`. Salvage is
// fixed at zero — the FORM spec (ADR 0037) collects only name, cost, accumulated depreciation, and
// remaining life; salvage is not a field on this form.
// -------------------------------------------------------------------------------------------------

export interface AssetRowInput {
  /** A stable REACT key (`crypto.randomUUID()`) — distinct from the row's idempotency key (derived
   *  from the form's per-submit id; see OpeningBalances.tsx), so editing a row never changes its
   *  React identity. */
  key: string
  name: string
  cost: string
  accumulated: string
  months: string
}

export function newAssetRow(): AssetRowInput {
  return { key: crypto.randomUUID(), name: '', cost: '', accumulated: '0', months: '60' }
}

/** A row counts as "touched" once its cost field has ANY content — an entirely blank row (the
 *  default first row) is silently ignored rather than validated, matching the FORM spec ("cost
 *  must be > 0 to include it"). Typing invalid content ("abc", "0", "-5") still counts as touched
 *  so {@link assetRowError} can flag it, rather than silently discarding a clear attempt to enter
 *  an asset. */
export function assetRowStarted(row: AssetRowInput): boolean {
  return row.cost.trim() !== ''
}

/**
 * Validates a touched row against the backend's constraints (name required, cost positive,
 * remaining life in [1, 600] months, accumulated depreciation in [0, cost] since salvage is fixed
 * at zero) — surfaced as an inline error rather than silently dropped, so a typo never loses data
 * the user already entered. Returns `null` for an untouched (blank-cost) row: there is nothing to
 * validate yet.
 */
export function assetRowError(
  row: AssetRowInput,
  currency: string,
): 'cost' | 'name' | 'accumulated' | 'months' | null {
  if (!assetRowStarted(row)) return null
  const costMinor = parseOpeningAmountInput(row.cost, currency)
  if (costMinor == null) return 'cost'
  if (!row.name.trim()) return 'name'
  const accumulatedMinor = parseOpeningAmountInput(row.accumulated, currency, { allowZero: true })
  if (accumulatedMinor == null || accumulatedMinor > costMinor) return 'accumulated'
  const months = Number(row.months)
  if (!Number.isInteger(months) || months < 1 || months > 600) return 'months'
  return null
}

/** The row's net book value (cost − accumulated depreciation) in minor units — 0 for a not-yet-
 *  started or invalid row, so it never distorts the live plug estimate. */
export function assetRowNetBookValueMinor(row: AssetRowInput, currency: string): number {
  if (assetRowError(row, currency) != null) return 0
  const costMinor = parseOpeningAmountInput(row.cost, currency)
  const accumulatedMinor = parseOpeningAmountInput(row.accumulated, currency, { allowZero: true })
  if (costMinor == null || accumulatedMinor == null) return 0
  return costMinor - accumulatedMinor
}

/**
 * Builds the POST /api/v1/assets/opening body for a started, VALID row — `null` for a blank row
 * (nothing to submit) or a started-but-invalid row (the caller blocks submit on
 * {@link assetRowError} first, so this is never reached for one of those).
 */
export function buildAssetRegistrationBody(
  row: AssetRowInput,
  asOfDate: string,
  currency: string,
): RegisterBroughtForwardAssetBody | null {
  if (!assetRowStarted(row) || assetRowError(row, currency) != null) return null
  const costMinor = parseOpeningAmountInput(row.cost, currency)
  const accumulatedMinor = parseOpeningAmountInput(row.accumulated, currency, { allowZero: true })
  if (costMinor == null || accumulatedMinor == null) return null
  return {
    name: row.name.trim(),
    asOfDate,
    costMinor,
    salvageMinor: 0,
    openingAccumulatedMinor: accumulatedMinor,
    remainingLifeMonths: Number(row.months),
    currency,
  }
}

/**
 * The LIVE, client-side "Opening Balance Equity (balancing plug)" estimate shown as the user
 * types: (Σ debit line amounts + Σ each started fixed asset's net book value) − Σ credit line
 * amounts, all in minor units. This mirrors — but is NOT identical to — the server's authoritative
 * `plugMinor`: the main opening-balance entry and each asset's registration are separate,
 * self-balancing postings server-side; this estimate simply shows the user the COMBINED economic
 * picture transparently while they fill in the form.
 */
export function computePlugMinor(
  values: Record<string, string>,
  rows: AssetRowInput[],
  currency: string,
): number {
  const lines = buildOpeningBalanceLines(values, currency)
  const assetDebitMinor = rows.reduce(
    (sum, row) => sum + assetRowNetBookValueMinor(row, currency),
    0,
  )
  return debitTotalMinor(lines) + assetDebitMinor - creditTotalMinor(lines)
}
