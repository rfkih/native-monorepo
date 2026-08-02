/**
 * Plain (non-component) expenses helpers — mirrors features/ar/format.ts / features/ap/format.ts /
 * features/bank/format.ts (each finance-adjacent feature keeps its own tiny formatter module so a
 * component file only exports components, react-refresh/only-export-components clean).
 */

import type { ExpenseClaimDetail } from './api'

/** Localized short date, e.g. `Jul 28, 2026` / `28 Jul 2026` — falls back to an em dash. */
export function formatDate(iso: string | null | undefined, locale: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(d)
}

/** Locale-aware plain integer count (grouping separators, no currency) — the org-unit hub's
 *  Expenses tab pending-claims tile (rule 9: every number through Intl, never string concatenation). */
export function formatCount(value: number, locale: string): string {
  return new Intl.NumberFormat(locale).format(value)
}

/**
 * Why "Pay now" (the DIRECT reimbursement, ADR 0030 §6) is disabled for this claim — an i18n key
 * under `expenses.actions.payDisabled.*`, or `null` when the action is enabled. Mirrors the
 * backend guard exactly: {@code ExpenseClaim#payDirect} requires APPROVED + unlinked.
 */
export function payDisabledReason(
  claim: Pick<ExpenseClaimDetail, 'status' | 'reimbursementRunId'>,
): 'expenses.actions.payDisabled.notApproved' | 'expenses.actions.payDisabled.linked' | null {
  if (claim.status !== 'APPROVED') return 'expenses.actions.payDisabled.notApproved'
  if (claim.reimbursementRunId) return 'expenses.actions.payDisabled.linked'
  return null
}

/**
 * Why "Void" (the correction path, ADR 0030 §5, Phase E7) is disabled for this claim — an i18n key
 * under `expenses.actions.voidDisabled.*`, or `null` when the action is enabled. Mirrors the
 * backend guard exactly: {@code ExpenseClaim#voidClaim} requires APPROVED + unlinked + unsettled.
 */
export function voidDisabledReason(
  claim: Pick<ExpenseClaimDetail, 'status' | 'reimbursementRunId' | 'settledAt'>,
):
  | 'expenses.actions.voidDisabled.notApproved'
  | 'expenses.actions.voidDisabled.linked'
  | 'expenses.actions.voidDisabled.settled'
  | null {
  if (claim.status !== 'APPROVED') return 'expenses.actions.voidDisabled.notApproved'
  if (claim.reimbursementRunId) return 'expenses.actions.voidDisabled.linked'
  if (claim.settledAt) return 'expenses.actions.voidDisabled.settled'
  return null
}
