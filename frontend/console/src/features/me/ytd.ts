/**
 * Pure year-to-date money-summing logic for the payslip YTD card (Track P Phase P10; P10 review
 * C1/S3), split out of {@code useMyPayslipsYtd} (`features/me/api.ts`) into a plain TS module so it
 * is unit-testable — this project's vitest config runs `*.test.ts` under a plain `node` environment
 * (no React rendering harness, mirroring `features/expenses/amount.ts`/`format.ts`) — and so the
 * "which lines count" rule lives in exactly ONE place, never re-derived at a call site.
 */

/** The subset of {@code MyPayslipDetail} the sum actually reads (kept structural so this module
 * imports nothing from `./api`, keeping it a leaf with zero React/query dependencies). */
export interface YtdDetailInput {
  grossMinor: number
  currency: string
  lines: { componentKey: string; kind: string; bearer: string; amountMinor: number }[]
}

export interface YtdTotals {
  grossMinor: number
  /** Σ every EMPLOYEE-borne PPH21 DEDUCTION line — a December true-up's line can be NEGATIVE (an
   * over-withheld refund, Track P Phase P3), which correctly REDUCES the total; that is real data,
   * not a display artifact to special-case away. */
  pph21Minor: number
  /** Null when given no details (nothing to derive a currency from). */
  currency: string | null
}

/**
 * Sums gross pay and PPh 21 withheld across a set of ALREADY-ACTIVE payslip run details — "active"
 * (POSTED, not superseded) is enforced upstream by the backend's `ACTIVE_RUN_PREDICATE` (P10 review
 * C1: `findMyPayslipHeaders` used to list every run that ever produced a line, including a
 * superseded correction re-run's stale lines and a CALCULATED-but-never-POSTED run's — this module
 * trusts the header list it is given is already the correct SET and only sums).
 *
 * PPh 21 withheld sums a line ONLY when it is `componentKey === 'PPH21'` AND `kind === 'DEDUCTION'`
 * AND `bearer === 'EMPLOYEE'` (P10 review S3 — the bearer check mirrors the backend's own liability
 * bucketing, which only ever books an EMPLOYEE-borne PPH21 line as the withholding; nothing in the
 * current dataset produces an EMPLOYER-borne or EARNING-kind PPH21-keyed line, so this is
 * robustness against a future/foreign shape, not a behavior change today).
 */
export function sumYtd(details: YtdDetailInput[]): YtdTotals {
  let grossMinor = 0
  let pph21Minor = 0
  let currency: string | null = null
  for (const detail of details) {
    grossMinor += detail.grossMinor
    currency = currency ?? detail.currency
    for (const line of detail.lines) {
      if (line.componentKey === 'PPH21' && line.kind === 'DEDUCTION' && line.bearer === 'EMPLOYEE') {
        pph21Minor += line.amountMinor
      }
    }
  }
  return { grossMinor, pph21Minor, currency }
}
