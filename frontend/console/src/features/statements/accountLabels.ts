/**
 * Chart-of-accounts code → i18n key, for the three GL-derived statements (Neraca / Laba Rugi /
 * Arus Kas). The ledger speaks in codes — `1900`, `5100`, `3900` — and the statement endpoints
 * return ONLY the code, so without this map a line renders its code twice ("1900 · 1900 · Rp …"),
 * which tells an owner nothing.
 *
 * Why not read the names finance-service already stores? Two reasons:
 *   1. `chart_of_account.name` is an English-only string (rule 9 wants localized UI copy), and
 *   2. the endpoint that serves it (`GET /api/v1/budgets/accounts`) is FINANCE_ROLES-gated
 *      (owner/accountant) while the statements are open to the wider REPORTS_ROLES — a manager
 *      would 403 and see bare codes.
 * So the console keeps its own localized name per account, exactly as `openingBalances/lines.ts`
 * already does for the eight opening-balance codes.
 *
 * `chart_of_account` is GLOBAL reference data seeded by finance-service Flyway migrations (no
 * `company_id`, no per-company rows), so this map is static and complete by construction — and
 * `__tests__/accountLabels.test.ts` parses those migrations and fails the build the moment a new
 * account is seeded without a name here.
 *
 * Values are FULL i18n keys, not fragments: no key is assembled by string concatenation at the
 * call site, so every key in this file is greppable.
 */
export const ACCOUNT_LABEL_KEYS: Readonly<Record<string, string>> = {
  // --- Assets -------------------------------------------------------------------------------
  '1000': 'statements.accounts.bank',
  '1100': 'statements.accounts.inventory',
  '1200': 'statements.accounts.accountsReceivable',
  '1250': 'statements.accounts.platformReceivable',
  '1300': 'statements.accounts.vatInput',
  '1310': 'statements.accounts.vatCarryforward',
  '1400': 'statements.accounts.prepaidExpense',
  '1500': 'statements.accounts.fixedAssetsCost',
  '1590': 'statements.accounts.accumulatedDepreciation',
  '1900': 'statements.accounts.cash',
  '1901': 'statements.accounts.qrisClearing',
  '1902': 'statements.accounts.cardClearing',

  // --- Liabilities --------------------------------------------------------------------------
  '2000': 'statements.accounts.accountsPayable',
  '2050': 'statements.accounts.grniClearing',
  '2100': 'statements.accounts.restaurantTaxPayable',
  '2110': 'statements.accounts.serviceChargePayable',
  '2200': 'statements.accounts.vatOutputPayable',
  '2300': 'statements.accounts.vatPayable',
  '2400': 'statements.accounts.deferredRevenue',
  '2500': 'statements.accounts.giftCardLiability',
  '2510': 'statements.accounts.loyaltyLiability',
  '2600': 'statements.accounts.employeeExpensePayable',
  '2610': 'statements.accounts.pph21Payable',
  '2620': 'statements.accounts.bpjsHealthPayable',
  '2630': 'statements.accounts.bpjsEmploymentPayable',
  '2640': 'statements.accounts.netWagesPayable',
  '2690': 'statements.accounts.otherPayrollDeductionsPayable',
  '2700': 'statements.accounts.otherLiabilities',

  // --- Equity -------------------------------------------------------------------------------
  '3000': 'statements.accounts.ownersCapital',
  '3100': 'statements.accounts.retainedEarningsPrior',
  '3900': 'statements.accounts.openingBalanceEquity',

  // --- Revenue ------------------------------------------------------------------------------
  '4000': 'statements.accounts.salesRevenue',
  '4010': 'statements.accounts.salesDiscount',
  '4020': 'statements.accounts.serviceChargeRevenue',
  '4030': 'statements.accounts.loyaltyRedemption',
  '4100': 'statements.accounts.interestIncome',
  '4200': 'statements.accounts.gainOnDisposal',
  '4300': 'statements.accounts.cashOver',
  '4900': 'statements.accounts.giftCardBreakage',

  // --- Expense ------------------------------------------------------------------------------
  '5000': 'statements.accounts.generalExpense',
  '5100': 'statements.accounts.costOfGoodsSold',
  '5200': 'statements.accounts.suppliesExpense',
  '5300': 'statements.accounts.utilitiesExpense',
  '5400': 'statements.accounts.bankCharges',
  '5500': 'statements.accounts.depreciationExpense',
  '5600': 'statements.accounts.lossOnDisposal',
  '5700': 'statements.accounts.cashShort',
  '5710': 'statements.accounts.platformFee',
  '5720': 'statements.accounts.qrisFee',
  '5730': 'statements.accounts.cardFee',
  '5800': 'statements.accounts.inventoryShrinkage',
  '6000': 'statements.accounts.salariesExpense',
  '6100': 'statements.accounts.bpjsEmployerExpense',
  '6110': 'statements.accounts.overtimeExpense',
  '6120': 'statements.accounts.commissionExpense',
  '6900': 'statements.accounts.unallocatedLabor',
  '9999': 'statements.accounts.suspense',

  // --- Synthetic rows (not chart accounts) ---------------------------------------------------
  // Profit earned and not yet taken out, which finance-service computes on read and appends to
  // every balance sheet under a code that deliberately cannot collide with a real one. NOT named
  // "retained earnings": account 3100 already carries that name for PRIOR years, and the two would
  // sit next to each other in Equity under near-identical labels.
  '3000-RETAINED-EARNINGS': 'statements.accounts.accumulatedProfit',
}

/**
 * The localized name for a GL account code, or `undefined` when the code has no name here (a
 * newly seeded account the map hasn't caught up with — the caller then shows the code alone
 * rather than repeating it in both columns).
 *
 * Takes the translate function rather than calling `useTranslation` so this module stays pure and
 * directly unit-testable (the house idiom, cf. `incomeDetail.ts`).
 */
export function accountLabel(
  translate: (key: string) => string,
  code: string,
): string | undefined {
  // `hasOwn`, not a bare lookup: a plain object literal inherits `constructor`, `toString` and the
  // rest, which would otherwise resolve as truthy "keys" and be handed to `translate`.
  if (!Object.hasOwn(ACCOUNT_LABEL_KEYS, code)) return undefined
  return translate(ACCOUNT_LABEL_KEYS[code])
}

/**
 * Every account code → its localized name, as the `ReadonlyMap` the P&L drill-down's pure
 * `detailRows` helper consumes. Build it once per render with `useMemo` keyed on `t`.
 */
export function accountLabelMap(translate: (key: string) => string): ReadonlyMap<string, string> {
  return new Map(Object.entries(ACCOUNT_LABEL_KEYS).map(([code, key]) => [code, translate(key)]))
}
