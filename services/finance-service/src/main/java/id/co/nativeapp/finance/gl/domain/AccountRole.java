package id.co.nativeapp.finance.gl.domain;

/**
 * A semantic role used in a {@link PostingTemplate} line, resolved to a concrete {@code
 * chart_of_account.account_code} via the {@code role_account_map} reference data. Roles are
 * SME-pluggable: an accountant seeds a new {@code role_account_map} row pointing an {@code
 * AccountRole} to the correct account for their jurisdiction without touching Java code.
 *
 * <p>The illustrative defaults seeded in V13/V15/V17 use a minimal set of roles sufficient for an
 * end-to-end balanced journal for each {@link EventKind}. The full Indonesian COA roles ({@code
 * TAX_PAYABLE}, {@code AR}, {@code AP}, etc.) are SME-gated.
 *
 * <p><strong>Phase 2 additions (pricing: tax + service charge + discount):</strong>
 *
 * <ul>
 *   <li>{@code GROSS_REVENUE} — the gross revenue credit line (= subtotal, before discount). Maps
 *       to the same revenue account as {@code REVENUE} in the illustrative seed.
 *   <li>{@code SALES_DISCOUNT} — the debit line for an order-level discount (contra-revenue). SME
 *       must confirm whether this is a contra-revenue account or a separate expense.
 *   <li>{@code SERVICE_CHARGE_REVENUE} — the credit line for the service charge when treated as
 *       revenue. SME NOTE: service charge may instead be a tip-pool liability; in that case, {@code
 *       SERVICE_CHARGE_PAYABLE} is the correct credit (defined but unused until SME confirmation).
 *   <li>{@code TAX_PAYABLE} — the credit line for the restaurant tax (PB1 / PPN regime is
 *       SME-gated; ILLUSTRATIVE placeholder seeded at ~10%).
 *   <li>{@code SERVICE_CHARGE_PAYABLE} — defined but UNUSED in Phase 2 posting templates; reserved
 *       for the SME-confirmed tip-pool-as-liability treatment.
 * </ul>
 */
public enum AccountRole {
  CASH_CLEARING,
  /** Illustrative clearing account for QRIS (QR-code) digital payments (ADR 0006, slice 2). */
  QRIS_CLEARING,
  /** Illustrative clearing account for CARD (debit/credit) payments (ADR 0006, slice 2). */
  CARD_CLEARING,
  REVENUE,
  /**
   * Phase 2: the gross revenue credit line (= subtotal, before discount). The debit of
   * SALES_DISCOUNT is the contra. Maps to the revenue account in the illustrative seed.
   */
  GROSS_REVENUE,
  /**
   * Phase 2: order-level discount (contra-revenue debit). SME must confirm account classification.
   * ILLUSTRATIVE placeholder maps to account 4010.
   */
  SALES_DISCOUNT,
  /**
   * Phase 2: service charge treated as revenue (credit). SME-gated: may be {@code
   * SERVICE_CHARGE_PAYABLE} if treated as a tip-pool liability. ILLUSTRATIVE maps to account 4020.
   */
  SERVICE_CHARGE_REVENUE,
  /**
   * Phase 2: tax payable credit (PB1 / PPN regime is SME-gated; ILLUSTRATIVE 10%). Maps to account
   * 2100 (ILLUSTRATIVE).
   */
  TAX_PAYABLE,
  /**
   * Phase 2: service charge as liability (tip pool). DEFINED but UNUSED in Phase 2 templates;
   * reserved for SME-confirmed tip-pool treatment. Maps to account 2110 (ILLUSTRATIVE).
   */
  SERVICE_CHARGE_PAYABLE,
  /**
   * Phase 1 AR: the Accounts Receivable control account. Debited when a customer invoice is issued
   * (the amount the customer owes), credited when a payment is received. Maps to account 1200
   * (ILLUSTRATIVE — SME-gated).
   */
  AR,
  /**
   * Phase 1 AR: output VAT payable — the credit for the tax leg of an issued customer invoice. The
   * PPN regime + rate are SME-gated (ILLUSTRATIVE placeholder computed in {@code InvoiceWriter}).
   * Maps to account 2200 (ILLUSTRATIVE).
   */
  VAT_OUTPUT,
  /**
   * Phase 2 AP: the Accounts Payable control account. Credited when a vendor bill is posted (the
   * amount owed to the vendor), debited when the bill is paid. Maps to account 2000 (ILLUSTRATIVE —
   * SME-gated).
   */
  AP,
  /**
   * Phase 2 AP: recoverable input VAT — the debit for the tax leg of a posted vendor bill (an
   * asset, VAT receivable from the tax authority). PPN regime + rate are SME-gated (ILLUSTRATIVE
   * placeholder computed in {@code BillWriter}). Maps to account 1300 (ILLUSTRATIVE).
   */
  VAT_INPUT,
  /**
   * Phase 3 bank: the single Bank control account (1000) that ALL bank accounts post to
   * (per-account balances live in the bank sub-ledger). Debited on a reconciled deposit, credited
   * on a reconciled withdrawal. Maps to account 1000 (ILLUSTRATIVE — SME-gated).
   */
  BANK,
  /**
   * Phase 3 bank: interest income (4100) — the contra credit for a deposit reconciled as INTEREST.
   * ILLUSTRATIVE — SME-gated.
   */
  INTEREST_INCOME,
  /**
   * Phase 3 bank: bank charges (5400) — the contra debit for a withdrawal reconciled as BANK_FEE.
   * ILLUSTRATIVE — SME-gated.
   */
  BANK_CHARGES,
  /**
   * Phase 4 tax (PPN): net VAT payable to the tax authority — the credit recognised when a PPN
   * return is filed for a period whose output VAT exceeds its input VAT (Dr VAT_OUTPUT / Cr
   * VAT_INPUT / Cr VAT_PAYABLE for the net), and debited again when the return is settled (Dr
   * VAT_PAYABLE / Cr CASH_CLEARING). Maps to account 2200-family settlement account 2300
   * (ILLUSTRATIVE — regime + account SME-gated).
   */
  VAT_PAYABLE,
  /**
   * Phase 4 tax (PPN): excess recoverable input VAT carried forward — the debit recognised when a
   * PPN return is filed for a period whose input VAT exceeds its output VAT (the Indonesian default
   * is <em>dikompensasikan</em>: carry the credit to the next period rather than claim an immediate
   * refund). Maps to account 1310 (ILLUSTRATIVE — regime + refund-vs-carryforward policy
   * SME-gated).
   */
  VAT_CREDIT_CARRYFORWARD,
  /**
   * Phase 6 assets: the fixed-asset cost control account — debited when an asset is acquired
   * (capitalized), the capex the cash-flow statement classifies as INVESTING. Maps to account 1500
   * (ILLUSTRATIVE — SME-gated).
   */
  FIXED_ASSET_COST,
  /**
   * Phase 6 assets: accumulated depreciation (a contra-asset) — credited by each monthly
   * depreciation run. Stays in the cash-flow OPERATING section (the non-cash add-back that offsets
   * the depreciation expense). Maps to account 1590 (ILLUSTRATIVE — SME-gated).
   */
  ACCUMULATED_DEPRECIATION,
  /**
   * Phase 6 assets: depreciation expense — debited by each monthly depreciation run. Maps to
   * account 5500 (ILLUSTRATIVE — SME-gated).
   */
  DEPRECIATION_EXPENSE,
  /**
   * Asset disposal (ADR 0022): gain on selling a fixed asset above book value — the "other income"
   * plug credited by the disposal entry ({@code proceeds − (cost − accumulated) > 0}). Never
   * operating revenue. Maps to account 4200 (ILLUSTRATIVE — SME-gated).
   */
  GAIN_ON_DISPOSAL,
  /**
   * Asset disposal (ADR 0022): loss on selling/scrapping a fixed asset below book value — the plug
   * debited by the disposal entry. Maps to account 5600 (ILLUSTRATIVE — SME-gated).
   */
  LOSS_ON_DISPOSAL,
  /**
   * Phase 6 deferrals: prepaid expense (an asset) — debited when an expense is paid up front,
   * credited as each month's share is amortized to EXPENSE. Maps to account 1400 (ILLUSTRATIVE —
   * SME-gated).
   */
  PREPAID_EXPENSE,
  /**
   * Phase 6 deferrals: deferred revenue (a liability) — credited when revenue is received up front,
   * debited as each month's share is recognized to REVENUE. Maps to account 2400 (ILLUSTRATIVE —
   * SME-gated).
   */
  DEFERRED_REVENUE,
  EXPENSE,
  LABOR_EXPENSE,
  LABOR_CLEARING,
  SUSPENSE,
  /**
   * Phase 4 loyalty/gift cards (ADR 0027, ILLUSTRATIVE — SME-gated): the gift-card liability
   * control account. Credited when a card is sold/topped up ({@code GIFT_CARD_SALE} — a LIABILITY
   * event, never revenue), debited as it is redeemed (the {@code GIFT_CARD_TENDER} leg of the
   * {@code SALE} v3 template). Outstanding balance = stored value the company still owes bearers.
   * Maps to account 2500 (ILLUSTRATIVE — SME-gated, V37).
   */
  GIFT_CARD_LIABILITY,
  /**
   * Phase 4 loyalty/gift cards (ADR 0027, ILLUSTRATIVE — SME-gated): points-redemption
   * contra-revenue debit, extending the {@code SALE} v3 template exactly as {@link #SALES_DISCOUNT}
   * does for the promo discount — points redemption is a discount, not a tender settlement (unlike
   * a gift card). SME must confirm contra-revenue vs. a separate marketing/loyalty expense
   * treatment. Maps to account 4030 (ILLUSTRATIVE — SME-gated, V37).
   */
  LOYALTY_DISCOUNT,
  /**
   * Phase 4 loyalty/gift cards (ADR 0027): accrued points liability. DEFINED-BUT-UNUSED in every
   * Phase 4 posting template — the {@link #SERVICE_CHARGE_PAYABLE} precedent (V17): an earn is
   * memo-only (no GL entry at earn time), so accruing this liability needs an SME-confirmed
   * points-valuation + breakage model not yet decided. Maps to account 2510 (ILLUSTRATIVE, V37).
   */
  LOYALTY_LIABILITY,
  /**
   * Phase 4 loyalty/gift cards (ADR 0027): unredeemed/expired gift-card value recognised as income.
   * DEFINED-BUT-UNUSED in every Phase 4 posting template — the {@link #SERVICE_CHARGE_PAYABLE}
   * precedent (V17): recognising breakage needs an SME-confirmed policy (rate + timing) not yet
   * decided. Maps to account 4900 (ILLUSTRATIVE, V37).
   */
  GIFT_CARD_BREAKAGE_INCOME,
  /**
   * Expense-claims program (ADR 0030, ILLUSTRATIVE — SME-gated): the employee expense payable
   * control account. Credited when a manager approves a claim (the expense recognition), debited
   * when the claim is voided (the exact contra) or settled (DIRECT pay or a POSTED payroll run).
   * Outstanding balance = what the company owes its employees for approved, un-settled claims. Maps
   * to account 2600 (ILLUSTRATIVE — SME-gated, V39).
   */
  EMPLOYEE_EXPENSE_PAYABLE,
  /**
   * Payroll liability recognition (ADR 0032, ILLUSTRATIVE -- SME-gated): PPh21 payable to the tax
   * office, recognised the moment a payroll run posts (Dr {@link #LABOR_CLEARING} / Cr this role).
   * MAY be posted as the Dr leg instead when the run's PPh21 bucket is negative (the December
   * Art-17 true-up refund month, ADR 0031). Maps to account 2610 (ILLUSTRATIVE -- SME-gated, V40).
   */
  PPH21_PAYABLE,
  /**
   * Payroll liability recognition (ADR 0032, ILLUSTRATIVE -- SME-gated): BPJS Kesehatan payable --
   * the employee-withheld leg PLUS the employer-contribution leg together (both are owed to the
   * SAME BPJS body). Maps to account 2620 (ILLUSTRATIVE -- SME-gated, V40).
   */
  BPJS_KES_PAYABLE,
  /**
   * Payroll liability recognition (ADR 0032, ILLUSTRATIVE -- SME-gated): BPJS Ketenagakerjaan
   * payable -- JHT + JP (employee-withheld PLUS employer-contribution legs) and JKK/JKM
   * (employer-only) together (all four programs are owed to the SAME BPJS body). Maps to account
   * 2630 (ILLUSTRATIVE -- SME-gated, V40).
   */
  BPJS_TK_PAYABLE,
  /**
   * Payroll liability recognition (ADR 0032, ILLUSTRATIVE -- SME-gated): unpaid net wages owed to
   * employees -- the run's net total, recognised as a liability the moment the run posts (rather
   * than assumed disbursed). Maps to account 2640 (ILLUSTRATIVE -- SME-gated, V40).
   */
  NET_WAGES_PAYABLE,
  /**
   * Payroll liability recognition (ADR 0032, ILLUSTRATIVE -- SME-gated): the catch-all for any
   * deduction line that is not PPh21 or a named BPJS leg -- e.g. a future custom component such as
   * a loan repayment. Maps to account 2690 (ILLUSTRATIVE -- SME-gated, V40).
   */
  OTHER_DEDUCTIONS_PAYABLE,

  /**
   * Register-close cash SHORT (selisih kas kurang, ADR 0036) — the expense side of the drawer
   * variance: Dr this / Cr CASH_CLEARING when the counted drawer is BELOW expected. Illustrative
   * seed 5700 (V43); SME-gated. Two roles (with CASH_OVER_INCOME) so an SME can keep them split or
   * remap both onto one netted "Selisih Kas" account — the GAIN/LOSS_ON_DISPOSAL precedent.
   */
  CASH_SHORT_EXPENSE,

  /**
   * Register-close cash OVER (selisih kas lebih, ADR 0036) — the other-income side: Dr
   * CASH_CLEARING / Cr this when the counted drawer EXCEEDS expected. Illustrative seed 4300 (V43);
   * SME-gated.
   */
  CASH_OVER_INCOME,

  /**
   * Phase B (ADR 0036) — the asset control account for money an ONLINE platform (GoFood/GrabFood
   * style) owes the merchant: the ONLINE-tender sale's clearing debit lands here GROSS (PSAK 72 —
   * the merchant is principal, the platform an agent), and the platform settlement credits it. One
   * shared GL account across channels; per-channel granularity lives in the {@code
   * platform_receivable} accumulator sub-ledger. Illustrative seed 1250 (V44); SME-gated.
   */
  PLATFORM_RECEIVABLE,

  /**
   * Phase B (ADR 0036) — the selling-expense side of a platform settlement's commission: Dr this
   * (fee = gross − net) + Dr CASH_CLEARING (net) / Cr PLATFORM_RECEIVABLE (gross). v1 books the
   * WHOLE fee to expense — not valid for a PKP claiming input VAT on the commission (the VAT_INPUT
   * split is an additive follow-up). Illustrative seed 5710 (V44); SME-gated.
   */
  PLATFORM_FEE_EXPENSE,

  /**
   * Opening balances (ADR 0037) — the paid-in / share capital an owner contributes: credited by the
   * opening balance-sheet entry for the portion of equity the user classifies as capital. Maps to
   * account 3000 (ILLUSTRATIVE — SME-gated, V46).
   */
  OWNER_CAPITAL,

  /**
   * Opening balances (ADR 0037) — accumulated profit from BEFORE go-live (prior years), credited by
   * the opening entry. Distinct from the balance sheet's synthetic current-year retained-earnings
   * line (computed on read from the P&amp;L under the literal code {@code
   * "3000-RETAINED-EARNINGS"}). Maps to account 3100 (ILLUSTRATIVE — SME-gated, V46).
   */
  RETAINED_EARNINGS,

  /**
   * Opening balances (ADR 0037) — the balancing clearing account (QuickBooks/Xero "Opening Balance
   * Equity"; Odoo "Undistributed Profits/Losses"). The opening entry auto-plugs its residual here
   * so it always balances, and each brought-forward asset credits it the asset's net book value. An
   * SME later reclassifies its balance into real capital/retained accounts. Maps to account 3900
   * (ILLUSTRATIVE — SME-gated, V46).
   */
  OPENING_BALANCE_EQUITY,

  /**
   * Inventory stocktake (ADR 0038 phase 3, ILLUSTRATIVE — SME-gated): the inventory asset control
   * account. A completed stocktake's signed net valued shrinkage debits/credits this against {@link
   * #INVENTORY_SHRINKAGE}: a net LOSS credits this (stock is worth less than the books show), a net
   * GAIN (found stock) debits this. Maps to account 1100 (ILLUSTRATIVE — SME-gated, V50) — the SAME
   * code the opening-balances placeholder (V46) already seeds for brought-forward inventory; a
   * fresh company has one inventory account either way.
   */
  INVENTORY,

  /**
   * Inventory stocktake (ADR 0038 phase 3, ILLUSTRATIVE — SME-gated): the shrinkage expense/income
   * account — debited by a net LOSS ({@code shrinkage_minor > 0}, {@code Dr this / Cr INVENTORY}),
   * credited by a net GAIN / found stock ({@code shrinkage_minor < 0}, {@code Dr INVENTORY / Cr
   * this}). Maps to account 5800 (ILLUSTRATIVE — SME-gated, V50).
   */
  INVENTORY_SHRINKAGE,

  /**
   * QRIS payments (ADR 0045, ILLUSTRATIVE — SME-gated): the MDR (merchant discount rate) fee
   * expense — the extra debit leg when a bank statement line is reconciled against {@link
   * id.co.nativeapp.finance.bank.domain.ReconciliationCategory#QRIS_CLEARING} ({@code
   * ReconciliationWriter}): {@code Dr BANK (net) + Dr this (MDR fee) / Cr QRIS_CLEARING (gross =
   * net + fee)}. Omitted entirely when the fee is zero — a zero-amount journal line is never
   * written (the {@code PlatformSettlementWriter} precedent). Maps to account 5720 (ILLUSTRATIVE —
   * SME-gated, V52).
   */
  QRIS_FEE_EXPENSE;

  /**
   * The GL clearing {@link AccountRole} a POS tender settles through — the single source shared by
   * the revenue-posting path ({@code RevenuePostingWriter.resolveClearingRole}) and the ADR-0038
   * daily-close reconciliation ({@code RegisterCloseWriter}): {@code CASH}/{@code null} → {@link
   * #CASH_CLEARING}; {@code QRIS} → {@link #QRIS_CLEARING}; {@code CARD} → {@link #CARD_CLEARING};
   * {@code ONLINE} → {@link #PLATFORM_RECEIVABLE}. Returns {@code null} for an unrecognised tender
   * so each caller picks its own fallback — the revenue path WARNs and defaults to cash (money is
   * never dropped), the register close treats an unknown non-cash tender as a poison event.
   */
  public static AccountRole clearingRoleForTender(String tenderType) {
    if (tenderType == null) {
      return CASH_CLEARING;
    }
    return switch (tenderType) {
      case "CASH" -> CASH_CLEARING;
      case "QRIS" -> QRIS_CLEARING;
      case "CARD" -> CARD_CLEARING;
      case "ONLINE" -> PLATFORM_RECEIVABLE;
      default -> null;
    };
  }
}
